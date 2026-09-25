@echo off
set SERVER=ws://172.20.20.10:9090
set CODE=WS-20
javaw -jar lan-alert-agent-1.0.0-jar-with-dependencies.jar %SERVER% %CODE%
