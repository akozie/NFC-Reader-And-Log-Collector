# NFC-Reader-And-Log-Collector

This app reads EMV (Europay, Mastercard, Visa) payment cards using NFC and extracts relevant information for logging and analysis. It safely collects non-sensitive data from cards, masks sensitive fields, and allows users to save or share transaction logs.
---

## 🚀 Features

-  PAN Masking: Masks sensitive card numbers (e.g., 411111******1111)
-  Amount and Currency: Reads transaction amount if available; allows user-supplied amount
-  Structured Logging: Saves transaction data in JSON format
-  Save and Share Logs: Save logs to device storage and share via standard Android intents.
-  Verbose Mode: Optional detailed logs for debugging and inspection.

---

## 📸 Screenshots

 <img width="336" height="662" alt="splashscreen" src="https://github.com/user-attachments/assets/a3893f3c-329c-46f9-926d-e139f47a6349" />
 <img width="334" height="691" alt="checkphoneNFC" src="https://github.com/user-attachments/assets/c13ccc3d-cdbe-4416-a797-5cb513d90711" />
 <img width="336" height="657" alt="waitingdialog" src="https://github.com/user-attachments/assets/c5474d3d-6b50-4ed5-b21f-69535c4b2b47" />
 <img width="336" height="657" alt="verboseoff" src="https://github.com/user-attachments/assets/22c4644a-728c-45b3-8c91-5c468f38a66d" />


---

## 🧪 Full Unit Test Coverage

Includes tests for:

- PAN Masking
- Expiry Formatting

---

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI**: XML Layouts
- **Build Tool**: Gradle

---

## 📦 Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/akozie/NFC-Reader-And-Log-Collector.git
