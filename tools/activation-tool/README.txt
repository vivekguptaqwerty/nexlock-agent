NexLock — Device Protection Activation Tool
=============================================

WHEN TO USE THIS
-----------------
Only when the normal QR code setup fails on a phone (some Samsung phones show
"Something went wrong" when scanning the QR code). If QR setup worked, you
don't need this tool at all.

ONE-TIME PHONE PREP (do this BEFORE running the tool)
--------------------------------------------------------
IMPORTANT: These steps must be done before the phone is protected — once
protected, they can no longer be turned on. Do this right after factory
resetting the phone, before anything else.

1. Factory reset the phone, and do NOT sign in to any Google or Samsung
   account when it restarts — just skip past those screens to the home
   screen.
2. Install the NexLock app on the phone (open the phone's browser and
   download it from the link your admin gave you).
3. On the phone, go to Settings > About phone, and tap "Build number" 7
   times in a row. A message will say "You are now a developer."
4. Go back to Settings, find "Developer options" (usually under System, or
   at the bottom of the main Settings list), open it, and turn ON
   "USB debugging."
5. Plug the phone into this computer with a USB cable.

RUNNING THE TOOL
-----------------
Windows: double-click "Activate Protection.bat"
Mac:     double-click "Activate Protection.command"

The FIRST time you run it, your computer may show a security warning
since this isn't from a registered software publisher yet — this is
normal, not a sign anything is wrong:

  Windows: click "More info", then "Run anyway."
  Mac: right-click the file, choose "Open", then click "Open" again in
       the popup. (After this first time, it will open normally.)

A window will open and a message may appear on the phone's screen asking
to trust this computer — tap "Allow." The tool will then do everything
else automatically and tell you when it's done.

Once it says "PROTECTION ACTIVATED SUCCESSFULLY," open the NexLock app on
the phone and enter the enrollment OTP to finish setup, same as usual.

TROUBLESHOOTING
-----------------
"COULD NOT FIND THE PHONE" — check the cable is a real data cable (not a
charge-only one), and that USB debugging is turned on (step 4 above).

"COULDN'T ACTIVATE PROTECTION" — the phone almost always needs a fresh
factory reset with no account added yet. If it still fails after a clean
reset, contact support.
