@echo off
if exist mvnw.cmd (
    call mvnw.cmd exec:java
) else (
    call mvn exec:java
)
