# AI Overlay Assistant

ফোনের যেকোনো অ্যাপ/গেমের উপর একটা floating bubble থাকবে। ট্যাপ করলে প্যানেল খুলবে —
সেখান থেকে screenshot নিয়ে AI কে (Gemini অথবা যেকোনো OpenAI-compatible API) পাঠানো যাবে,
এবং AI এর উত্তর একই bubble panel এ দেখাবে। Auto mode চালু করলে প্রতি কয়েক সেকেন্ডে
নিজে থেকেই screenshot নিয়ে বিশ্লেষণ করবে (এটাই বাস্তবসম্মত "live watching")।

## কেন Termux দিয়ে সরাসরি APK বানানো হচ্ছে না

Termux এ পুরো Android SDK + Gradle বসিয়ে reliable ভাবে APK build করা প্রযুক্তিগতভাবে
খুব ভঙ্গুর (অনেক পুরনো/অসামঞ্জস্যপূর্ণ প্যাকেজ সমস্যা হয়)। তাই প্রফেশনাল approach:
**Termux ব্যবহার করে কোড GitHub এ push করা, আর build হবে GitHub Actions এ (cloud এ, ফ্রি)।**
এতে APK সবসময় সঠিকভাবে build হবে, কোনো local dependency সমস্যা থাকবে না।

## ধাপে ধাপে সেটআপ

### ১. GitHub এ রিপো বানান
- github.com এ গিয়ে নতুন একটা **private repository** বানান, নাম দিন যেমন `AiOverlayAssistant`

### ২. Termux এ git সেটআপ করুন
```bash
pkg update && pkg upgrade
pkg install git openssh
git config --global user.name "আপনার নাম"
git config --global user.email "আপনার ইমেইল"
```

### ৩. এই প্রজেক্ট ফোল্ডারটা Termux এ আনুন
এই চ্যাট থেকে ডাউনলোড হওয়া zip ফাইলটা `~/storage/downloads/` এ পাবেন
(প্রথমবার `termux-setup-storage` চালিয়ে storage permission দিন)।
```bash
termux-setup-storage
cd ~
unzip ~/storage/downloads/AiOverlayAssistant.zip
cd AiOverlayAssistant
```

### ৪. GitHub এ push করুন
```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<আপনার-ইউজারনেম>/AiOverlayAssistant.git
git push -u origin main
```
(প্রথমবার push করার সময় GitHub আপনাকে login/token চাইবে — GitHub এর
Settings → Developer settings → Personal access token বানিয়ে password এর জায়গায় সেটা দিন)

### ৫. APK build হওয়া দেখুন
GitHub repo তে গিয়ে **Actions** ট্যাবে ক্লিক করুন। push করার সাথে সাথেই build শুরু হয়ে
যাবে (প্রায় ২-৩ মিনিট লাগবে)। শেষ হলে সেই workflow run এ ঢুকে নিচে
**Artifacts → ai-overlay-assistant-debug** থেকে zip ডাউনলোড করুন — ভেতরে `app-debug.apk` পাবেন।

### ৬. ফোনে ইনস্টল করুন
- APK ফোনে নিয়ে ট্যাপ করুন, "Install from unknown sources" allow করুন
- App খুলুন

## App এর ভেতরে যা করতে হবে

1. **AI Provider** বাছুন — Gemini (ফ্রি) অথবা OpenAI-compatible
2. **API Key** বসান:
   - Gemini: https://aistudio.google.com/apikey এ গিয়ে ফ্রি key বানান (credit card লাগে না)
3. **Model name**: Gemini হলে `gemini-2.5-flash` লিখুন (ফ্রি tier এ ভালো কাজ করে)
4. **Default command**: bubble এ ট্যাপ করলে যে instruction টা AI কে যাবে, সেটা লিখে রাখুন —
   এতে বারবার লিখতে হবে না
5. **Auto-mode interval**: কত সেকেন্ড পরপর screenshot নিবে (৫ সেকেন্ড রাখাই ভালো, কম দিলে
   দ্রুত ফ্রি API limit শেষ হয়ে যাবে)
6. **Save Settings** চাপুন
7. **"Overlay Permission diye Start koro"** চাপুন — দুইটা permission চাইবে:
   - Overlay/floating window permission (settings এ allow করুন, ফিরে এসে আবার Start চাপুন)
   - Screen recording permission (Start now চাপুন)
8. এখন bubble টা যেকোনো app/game এর উপর ভাসবে। ট্যাপ করলে প্যানেল খুলবে —
   সেখানে "Ask now" বা saved command এ ট্যাপ করে সাহায্য নিতে পারবেন, অথবা
   Auto mode চালু করে দিলে এটা নিজে থেকেই চলতে থাকবে।

## গুরুত্বপূর্ণ সীমাবদ্ধতা (honestly বলছি)

- Gemini এর ফ্রি tier এ প্রতি মিনিটে/দিনে request limit আছে (মডেল অনুযায়ী ভিন্ন,
  আজকের হিসেবে Flash-Lite মডেলে প্রতি মিনিটে প্রায় ১৫টা, দিনে প্রায় হাজারখানেক request)।
  তাই Auto mode এ ৫ সেকেন্ডের কমে interval না রাখাই ভালো।
- Auto mode চালু থাকলে battery ও data ব্যবহার বাড়বে — এটা screenshot + internet call এর
  স্বাভাবিক খরচ, এড়ানো যায় না।
- কিছু game (যেগুলো DRM/secure surface ব্যবহার করে) screenshot এ কালো স্ক্রিন দেখাতে পারে —
  এটা Android এর নিরাপত্তা সীমাবদ্ধতা, অ্যাপ দিয়ে পাশ কাটানো সম্ভব না।

## ভবিষ্যতে যোগ করতে পারেন
- একাধিক saved command এর জন্য add/delete UI (এখন কোড থেকে default তালিকা আছে)
- Voice output (text-to-speech দিয়ে উত্তর শোনানো)
- Response history save রাখা
