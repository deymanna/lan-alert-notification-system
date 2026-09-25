@echo off
cd /d %~dp0
mvn clean package
java -jar target\lan-alert-server-1.0.0-jar-with-dependencies.jar
pause
