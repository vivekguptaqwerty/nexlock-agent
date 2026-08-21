#!/bin/bash
# NexLock Device Protection Activation Tool (Mac)
# Double-click this file to run. No typing needed.

cd "$(dirname "$0")"
ADB="./platform-tools/adb"
PACKAGE="com.nexlock.agent"
ADMIN_COMPONENT="com.nexlock.agent/.service.NexLockDeviceAdminReceiver"

clear
echo ""
echo "=========================================="
echo "   NexLock — Device Protection Activation"
echo "=========================================="
echo ""

# --- Step 1: wait for the phone to show up and be trusted ---
echo "Step 1 of 3: Looking for your phone..."
echo "(Make sure it's plugged in with a USB cable.)"
echo ""

FOUND=""
for i in $(seq 1 30); do
    STATE=$("$ADB" get-state 2>/dev/null)
    if [ "$STATE" = "device" ]; then
        FOUND="yes"
        break
    fi
    if [ "$STATE" = "unauthorized" ]; then
        echo "A message appeared on the phone's screen asking to trust this computer."
        echo "Please tap 'Allow' on the phone now, then wait..."
    fi
    sleep 1
done

if [ -z "$FOUND" ]; then
    echo ""
    echo "----------------------------------------------------"
    echo "COULD NOT FIND THE PHONE."
    echo ""
    echo "Please check:"
    echo "  1. The USB cable is properly plugged in on both ends"
    echo "  2. On the phone, go to Settings and make sure"
    echo "     'USB debugging' is turned ON under Developer Options"
    echo "  3. If a popup appeared on the phone, tap 'Allow'"
    echo "----------------------------------------------------"
    echo ""
    read -p "Press Enter to close this window..."
    exit 1
fi

echo "Phone found!"
echo ""

# --- Step 2: confirm the NexLock app is installed ---
echo "Step 2 of 3: Checking the NexLock app is installed..."
INSTALLED=$("$ADB" shell pm list packages 2>/dev/null | grep -c "$PACKAGE")

if [ "$INSTALLED" -eq 0 ]; then
    echo ""
    echo "----------------------------------------------------"
    echo "THE NEXLOCK APP ISN'T INSTALLED ON THIS PHONE YET."
    echo ""
    echo "Please install the NexLock app on the phone first"
    echo "(open the phone's browser and download it from the"
    echo "link your admin gave you), then run this tool again."
    echo "----------------------------------------------------"
    echo ""
    read -p "Press Enter to close this window..."
    exit 1
fi

echo "App found!"
echo ""

# --- Step 3: activate protection ---
echo "Step 3 of 3: Activating protection..."
echo ""
RESULT=$("$ADB" shell dpm set-device-owner "$ADMIN_COMPONENT" 2>&1)

if echo "$RESULT" | grep -qi "Success"; then
    echo "=========================================="
    echo "   PROTECTION ACTIVATED SUCCESSFULLY"
    echo "=========================================="
    echo ""
    echo "Next step: open the NexLock app on the phone"
    echo "and enter the enrollment OTP to finish setup."
elif echo "$RESULT" | grep -qi "already"; then
    echo "=========================================="
    echo "   This phone is already protected."
    echo "=========================================="
    echo ""
    echo "No action needed — open the NexLock app to check its status."
elif echo "$RESULT" | grep -qi "not allowed\|provisioning"; then
    echo "=========================================="
    echo "   COULDN'T ACTIVATE PROTECTION"
    echo "=========================================="
    echo ""
    echo "This usually means the phone was NOT freshly factory reset,"
    echo "or a Google/Samsung account was already added to it."
    echo ""
    echo "Please factory reset the phone, skip adding any account"
    echo "when it restarts, install the NexLock app, then try this"
    echo "tool again."
else
    echo "=========================================="
    echo "   COULDN'T ACTIVATE PROTECTION"
    echo "=========================================="
    echo ""
    echo "Something unexpected happened. Please contact support"
    echo "and share this message:"
    echo ""
    echo "$RESULT"
fi

echo ""
read -p "Press Enter to close this window..."
