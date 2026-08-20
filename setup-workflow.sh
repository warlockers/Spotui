#!/bin/bash

# Skript pro manuální vytvoření GitHub Actions workflow
# Tento skript vytvoří soubor .github/workflows/build-apk.yml

# Vytvoř .github/workflows adresář
mkdir -p .github/workflows

# Vytvoř build-apk.yml soubor
cat > .github/workflows/build-apk.yml << 'EOF'
name: Build APK

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout repository
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle

    - name: Make gradlew executable
      run: chmod +x ./gradlew

    - name: Build Release APK
      run: ./gradlew assembleRelease

    - name: Upload APK to Artifacts
      uses: actions/upload-artifact@v4
      with:
        name: spotui-release-apk
        path: app/build/outputs/apk/release/Spotui_v*.apk
        retention-days: 30

    - name: Upload Build Log
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: build-logs
        path: app/build/outputs/
        retention-days: 7
EOF

echo "✓ .github/workflows/build-apk.yml vytvořen"
echo ""
echo "Příkazy pro push do repozitáře:"
echo "git add .github/workflows/build-apk.yml"
echo "git commit -m 'Add GitHub Actions workflow for building APK'"
echo "git push origin main"
