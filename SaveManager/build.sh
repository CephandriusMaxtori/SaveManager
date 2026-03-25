export JAVA_HOME=/usr/lib/jvm/jdk-21.0.10-oracle-x64
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build -x test
./gradlew remapJar