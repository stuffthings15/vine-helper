import re
import os
import subprocess
import json
from datetime import datetime

class RegressionTester:
    def __init__(self, workspace_dir):
        self.workspace_dir = workspace_dir
        self.main_screen_path = os.path.join(workspace_dir, "app", "src", "main", "java", "com", "example", "vinehelper", "ui", "main", "MainScreen.kt")
        self.main_activity_path = os.path.join(workspace_dir, "app", "src", "main", "java", "com", "example", "vinehelper", "MainActivity.kt")
        self.adb_path = r"C:\Users\stuff\AppData\Local\Android\Sdk\platform-tools\adb.exe"
        self.issues = []

    def log_issue(self, category, description):
        self.issues.append({"category": category, "description": description, "timestamp": datetime.now().isoformat()})
        print(f"[-] REGRESSION ISSUE [{category}]: {description}")

    def log_success(self, category, message):
        print(f"[+] SUCCESS [{category}]: {message}")

    def verify_main_screen(self):
        print("\n--- Verifying MainScreen.kt (Unified Feed & recId passing) ---")
        if not os.path.exists(self.main_screen_path):
            self.log_issue("Static Analysis", f"MainScreen.kt not found at {self.main_screen_path}")
            return

        with open(self.main_screen_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Check if filteredItems directly uses items
        match = re.search(r"val\s+filteredItems\s*=\s*items", content)
        if match:
            self.log_success("Static Analysis", "MainScreen.kt uses unified filteredItems list.")
        else:
            self.log_issue("Static Analysis", "MainScreen.kt is missing the unified feed filteredItems layout.")

        # Check if onSeeDetailsClick passes recId
        if "onSeeDetailsClick(item.queue, item.asin, item.recId)" in content:
            self.log_success("Static Analysis", "MainScreen.kt passes item.recId to onSeeDetailsClick.")
        else:
            self.log_issue("Static Analysis", "MainScreen.kt does not pass item.recId to onSeeDetailsClick.")

    def verify_main_activity(self):
        print("\n--- Verifying MainActivity.kt (Modal styling & race condition fixes) ---")
        if not os.path.exists(self.main_activity_path):
            self.log_issue("Static Analysis", f"MainActivity.kt not found at {self.main_activity_path}")
            return

        with open(self.main_activity_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Check for CSS popover height override
        if "height: 55% !important" in content:
            self.log_success("Static Analysis", "Popover height override of 55% is defined.")
        else:
            self.log_issue("Static Analysis", "Popover height override (55%) is missing.")

        # Check for image constraints
        if "max-height: 80px !important" in content:
            self.log_success("Static Analysis", "Hero image height constraints are defined in WebView styles.")
        else:
            self.log_issue("Static Analysis", "Hero image height constraints are missing in WebView styles.")

        # Check for expander display none
        if "#vvp-product-description-expander { display: none !important;" in content or "display: none !important; /* Hide long list details" in content:
            self.log_success("Static Analysis", "Product description expander is hidden to save vertical space.")
        else:
            self.log_issue("Static Analysis", "Product description expander hide override is missing.")

        # Check for pending click handling on cold-start
        if "pendingAsinClick" in content and "pendingQueueClick" in content:
            self.log_success("Static Analysis", "WebView click race condition logic is implemented.")
        else:
            self.log_issue("Static Analysis", "WebView click race condition variables/logic are missing.")

        # Check for target queue comparison in onPageFinished
        if "currentQueue = currentUri?.getQueryParameter(\"queue\")" in content and "targetQueue == currentQueue" in content:
            self.log_success("Static Analysis", "onPageFinished checks queue query parameters to prevent queue page mismatches.")
        else:
            self.log_issue("Static Analysis", "onPageFinished does not verify queue parameter match before script injection.")

        # Check for prioritization of data-recommendation-id in click script
        if "data-recommendation-id" in content and "input = document.querySelector('input[data-recommendation-id=\"$recId\"]')" in content:
            self.log_success("Static Analysis", "Details click script prioritizes data-recommendation-id over data-asin.")
        else:
            self.log_issue("Static Analysis", "Details click script does not prioritize data-recommendation-id.")

    def verify_emulator_status(self):
        print("\n--- Verifying Emulator & Package Status ---")
        if not os.path.exists(self.adb_path):
            self.log_issue("Device Verification", f"ADB not found at {self.adb_path}")
            return

        try:
            # Check devices
            res = subprocess.run([self.adb_path, "devices"], capture_output=True, text=True, check=True)
            devices = [line for line in res.stdout.splitlines() if "device" in line and "devices" not in line]
            if not devices:
                self.log_issue("Device Verification", "No active emulator found.")
                return
            self.log_success("Device Verification", f"Active device connected: {devices[0]}")

            # Check if package is installed
            res_pkg = subprocess.run([self.adb_path, "shell", "pm", "list", "packages", "com.example.vinehelper"], capture_output=True, text=True)
            if "com.example.vinehelper" in res_pkg.stdout:
                self.log_success("Device Verification", "com.example.vinehelper is installed on the device.")
            else:
                self.log_issue("Device Verification", "com.example.vinehelper package is not installed on the device.")

        except Exception as e:
            self.log_issue("Device Verification", f"ADB execution failed: {str(e)}")

    def run_all(self):
        self.verify_main_screen()
        self.verify_main_activity()
        self.verify_emulator_status()

        # Write report
        report_path = os.path.join(self.workspace_dir, "regression_test_report.json")
        report_data = {
            "timestamp": datetime.now().isoformat(),
            "status": "PASSED" if not self.issues else "FAILED",
            "issues_count": len(self.issues),
            "issues": self.issues
        }
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)
        print(f"\n[+] Regression test completed. Report written to: {report_path}")

if __name__ == '__main__':
    tester = RegressionTester(r"C:\Users\stuff\Documents\Vine Helper")
    tester.run_all()
