package com.choculaterie.gui;

import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.util.ScreenUtils;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.ToastManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AccountLinkingScreen extends Screen {
    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;

    private String currentFlowId = null;
    private String pendingLinkCode = null;
    private String isLinkingStatus = "";
    private boolean isLinking = false;
    private String pendingAuthUrl = null;
    private ScheduledExecutorService pollExecutor = null;
    private CustomButton linkBtn = null;
    private CustomButton copyUrlBtn = null;

    public AccountLinkingScreen(Screen parent) {
        super(Text.literal("Link Your Account"));
        this.parent = parent;
        this.toastManager = new ToastManager(null);
    }

    @Override
    protected void init() {
        toastManager.initClient(client);

        int btnSize = 20, margin = 6;
        addDrawableChild(new CustomButton(margin, margin, btnSize, btnSize, Text.literal("←"), b -> goBack()));

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        int cx = this.width / 2, btnW = 100;
        int btnY = this.height / 2 - 10;
        
        linkBtn = new CustomButton(cx - btnW / 2, btnY, btnW, 20,
                Text.literal(hasKey ? "Reset" : "Link Account"),
                b -> handleLinkOrReset(hasKey));
        addDrawableChild(linkBtn);

        copyUrlBtn = new CustomButton(cx - btnW / 2, btnY, btnW, 20,
                Text.literal("Copy URL"), b -> copyAuthUrl());
        copyUrlBtn.visible = false;
        addDrawableChild(copyUrlBtn);

        if (hasKey) networkManager.setApiKey(apiKey);
    }

    private void handleLinkOrReset(boolean hasKey) {
        if (hasKey) {
            networkManager.setApiKey(null);
            ConfigManager.clearApiKey();
            client.setScreen(new AccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void startOAuthFlow() {
        if (isLinking) return;
        isLinking = true;
        isLinkingStatus = "Initiating...";

        networkManager.initiateOAuthFlow("SaveManager Mod").whenComplete((json, err) -> {
            if (err != null) {
                runOnClient(mc -> { isLinking = false; isLinkingStatus = ""; });
                return;
            }
            try {
                currentFlowId = json.has("flowId") ? json.get("flowId").getAsString() : null;
                int expiresIn = json.has("expiresInSeconds") ? json.get("expiresInSeconds").getAsInt() : 300;
                
                if (currentFlowId == null) {
                    runOnClient(mc -> { isLinking = false; isLinkingStatus = ""; });
                    return;
                }

                String authUrl = networkManager.getOAuthAuthorizeUrl(currentFlowId);
                
                runOnClient(mc -> {
                    pendingAuthUrl = authUrl;
                    if (linkBtn != null) linkBtn.visible = false;
                    if (copyUrlBtn != null) copyUrlBtn.visible = true;
                    isLinkingStatus = "Waiting for approval...";

                    // Use the standard Minecraft Link Opening Screen
                    mc.setScreen(new ConfirmLinkScreen(confirmed -> {
                        if (confirmed) {
                            Util.getOperatingSystem().open(authUrl);
                        }
                        // Return to this screen regardless of choice to continue polling
                        mc.setScreen(this);
                    }, authUrl, true));
                });

                startPolling(currentFlowId, expiresIn);
            } catch (Exception e) {
                runOnClient(mc -> { isLinking = false; isLinkingStatus = ""; });
            }
        });
    }

    private void startPolling(String flowId, int timeoutSeconds) {
        stopPolling();
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SaveManager-OAuth-Poll");
            t.setDaemon(true);
            return t;
        });

        final int[] attempts = {0};
        final int maxAttempts = timeoutSeconds / 2;

        pollExecutor.scheduleAtFixedRate(() -> {
            if (++attempts[0] >= maxAttempts) {
                runOnClient(mc -> { stopPolling(); isLinking = false; isLinkingStatus = ""; });
                return;
            }
            networkManager.getOAuthFlowStatus(flowId).whenComplete((json, err) -> {
                if (err != null) return;
                runOnClient(mc -> handlePollResponse(json, mc));
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void handlePollResponse(com.google.gson.JsonObject json, MinecraftClient mc) {
        String status = json.has("status") ? json.get("status").getAsString() : "pending";

        switch (status) {
            case "expired" -> { stopPolling(); isLinking = false; isLinkingStatus = ""; }
            case "cancelled" -> {
                stopPolling();
                isLinking = false;
                isLinkingStatus = "§cCancelled";
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> runOnClient(m -> isLinkingStatus = ""));
            }
            case "pending" -> isLinkingStatus = "Waiting for approval...";
            case "completed" -> handleCompleted(json, mc);
        }
    }

    private void handleCompleted(com.google.gson.JsonObject json, MinecraftClient mc) {
        String saveKey = json.has("saveKey") ? json.get("saveKey").getAsString() : null;
        if (saveKey == null) return;

        boolean isMinecraftLinked = json.has("isMinecraftLinked") && json.get("isMinecraftLinked").getAsBoolean();
        boolean linkingComplete = json.has("minecraftLinkingComplete") && json.get("minecraftLinkingComplete").getAsBoolean();
        String linkCode = json.has("linkCode") && !json.get("linkCode").isJsonNull() ? json.get("linkCode").getAsString() : null;

        if (isMinecraftLinked) {
            stopPolling();
            completeLinking(saveKey);
            return;
        }
        if (linkingComplete) {
            stopPolling();
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Linking complete"));
            }
            CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> runOnClient(m -> completeLinking(saveKey)));
            return;
        }
        if (linkCode != null && !linkCode.equals(pendingLinkCode)) {
            pendingLinkCode = linkCode;
            isLinkingStatus = "Linking MC account...";
            autoJoinServerAndLink(linkCode);
        }
    }

    private void autoJoinServerAndLink(String linkCode) {
        isLinkingStatus = "Joining server...";
        runOnClient(mc -> {
            try {
                var serverAddress = net.minecraft.client.network.ServerAddress.parse("mc.choculaterie.com");
                var serverInfo = new net.minecraft.client.network.ServerInfo(
                        "Choculaterie", "mc.choculaterie.com", net.minecraft.client.network.ServerInfo.ServerType.OTHER);

                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(this, mc, serverAddress, serverInfo, false, null);
                scheduleLinkCommand(mc, linkCode, 6);
            } catch (Exception e) {
                isLinking = false;
                isLinkingStatus = "";
            }
        });
    }

    private void scheduleLinkCommand(MinecraftClient mc, String linkCode, int delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> runOnClient(m -> {
            if (m.player != null && m.player.networkHandler != null) {
                isLinkingStatus = "Sending link command...";
                m.player.networkHandler.sendChatCommand("link " + linkCode);
            } else if (delaySeconds == 6) {
                scheduleLinkCommand(m, linkCode, 3);
            }
        }));
    }

    private void completeLinking(String saveKey) {
        stopPolling();
        isLinking = false;
        isLinkingStatus = "";
        pendingLinkCode = null;
        currentFlowId = null;

        networkManager.setApiKey(saveKey);
        ConfigManager.saveApiKey(saveKey);

        runOnClient(mc -> {
            SaveManagerScreen screen = (parent instanceof SaveManagerScreen sms) ? sms : new SaveManagerScreen(parent);
            mc.setScreen(screen);
            screen.refresh();
        });
    }

    private void goBack() {
        stopPolling();
        String apiKey = ConfigManager.loadApiKey();
        runOnClient(mc -> {
            if (parent instanceof SaveManagerScreen sms && (apiKey == null || apiKey.isBlank())) {
                mc.setScreen(new SelectWorldScreen(ScreenUtils.resolveRootParent(parent)));
            } else {
                mc.setScreen(parent);
            }
        });
    }

    private void stopPolling() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    private void copyAuthUrl() {
        if (pendingAuthUrl != null && client != null && client.keyboard != null) {
            client.keyboard.setClipboard(pendingAuthUrl);
            toastManager.showSuccess("URL copied! Paste it in your browser.");
        }
    }

    private void runOnClient(java.util.function.Consumer<MinecraftClient> action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.execute(() -> action.accept(mc));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int btnY = this.height / 2 - 10;

        context.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        if (hasKey && !isLinking) {
            context.drawCenteredTextWithShadow(textRenderer, "§aAccount linked ✓", cx, btnY - 20, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, "Reset to unlink and connect a different account.", cx, btnY + 30, 0xFF888888);
        } else if (!isLinking) {
            int stepY = btnY + 32;
            context.drawCenteredTextWithShadow(textRenderer, "How it works:", cx, stepY, 0xFF999999);
            context.drawCenteredTextWithShadow(textRenderer, "1. A browser window will open. Sign in and click Approve.", cx, stepY + 16, 0xFFCCCCCC);
            context.drawCenteredTextWithShadow(textRenderer, "2. The game will briefly join a server to verify your Minecraft account.", cx, stepY + 28, 0xFFCCCCCC);
            context.drawCenteredTextWithShadow(textRenderer, "3. Once verified, you're ready to sync your saves!", cx, stepY + 40, 0xFFCCCCCC);
        } else {
            if (!isLinkingStatus.isEmpty()) {
                context.drawCenteredTextWithShadow(textRenderer, isLinkingStatus, cx, btnY - 20, 0xFF88FF88);
            }
            if (pendingAuthUrl != null) {
                context.drawCenteredTextWithShadow(textRenderer, "Browser didn't open? Copy the URL and paste it manually.", cx, btnY + 30, 0xFF888888);
            }
        }
        toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public void removed() { stopPolling(); }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void close() { goBack(); }
}