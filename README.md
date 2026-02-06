ResQ — Personal Safety Hardware Device

🚨 Overview

ResQ is a discreet personal safety device designed for emergency situations. With a simple triple-press of the SOS button, the device automatically sends your live location and ambient audio recording to predefined emergency contacts via SMS and email.

✨ Key Features

Instant Emergency Alert: Triple-press SOS button triggers immediate distress signal
Real-time Location Tracking: GPS coordinates sent with Google Maps link
Ambient Audio Capture: 30-second audio recording provides context
Dual Notification System: Alerts sent via SMS + email for redundancy
Compact & Discreet: Pocket-sized design for everyday carry
Long Battery Life: Up to 7 days standby on single charge
🛠️ Technical Architecture

Hardware Components

text
- ESP32 Microcontroller (Wi-Fi/Bluetooth capabilities)
- GPS Module (NEO-6M for precise location tracking)
- MEMS Microphone (high-sensitivity audio capture)
- Tactile SOS Button (water-resistant, 1M press cycles)
- 1000mAh Li-Po Battery with charging circuit
- Vibration Motor (silent feedback confirmation)
Software Stack

Firmware: C++ (Arduino framework)
Cloud Service: AWS IoT Core for device management
Backend: Python Flask API for message processing
SMS Gateway: Twilio API integration
Email Service: SendGrid SMTP
📱 How It Works

User Flow

Setup: User registers device via mobile app, adds emergency contacts
Emergency: User triple-presses SOS button
Activation: Device records location + audio, connects to nearest Wi-Fi
Transmission: Data sent to cloud, processed, and forwarded to contacts
Confirmation: Vibration feedback confirms successful alert
Emergency Alert Content

text
📍 Location: 40.7128° N, 74.0060° W
   Map: https://maps.google.com/?q=40.7128,74.0060
   
🎙️ Audio: 30-second recording attached
   
⏰ Time: 2025-01-15 14:30:22 UTC
   
🆔 Device ID: RESQ-7B3K9
🏆 Achievements & Recognition

Secured $1,000 funding from Bakai Bank startup competition
Featured in Kyrgyzstan Tech Innovation Forum 2024
User testing completed with 50 participants (95% satisfaction rate)
🎯 Target Users

Students walking home late
Night shift workers
Senior citizens living alone
Travelers in unfamiliar areas
Domestic violence survivors
🔒 Privacy & Security

End-to-end encryption for all transmitted data
No continuous tracking — location only sent during emergencies
User-controlled data: All recordings deleted after 72 hours
Compliance: GDPR-ready architecture
📊 Impact Metrics

Response time: Average 2.1 minutes to contact emergency services
Reliability: 99.8% successful alert transmission rate
Accessibility: Designed for users with limited tech literacy
Cost: Target production cost <$25 per unit
🚀 Future Development

Phase 2 (2025)

Bluetooth mesh networking for offline alerts
Integration with local emergency services APIs
Wearable form factor (bracelet/watch)
Multi-language support
Phase 3 (2026)

AI-powered threat detection (audio pattern recognition)
Crowdsourced safety maps
Integration with smart home systems
Enterprise version for corporate safety
👥 Team

Altynai Akylbekova – Founder & Lead Developer

Hardware design & firmware development
Cloud architecture & API integration
User experience design
