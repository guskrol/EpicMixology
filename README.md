# EpicMixology

EpicBot script for the Old School RuneScape Mastering Mixology minigame.

## Module

`mixology-profit` contains the complete script, including the Mixology workflow, banking, Grand Exchange restocking, travel, inventory handling, and diagnostics.

## Build

```powershell
.\gradlew.bat :mixology-profit:build
```

The EpicBot Gradle plugin copies the compiled classes to the local EpicBot scripts directory configured on the machine.

## Run Locally

Open EpicBot, refresh local scripts, and select **Mixology Profit**. The running version is printed in the script overlay and log when it starts.
