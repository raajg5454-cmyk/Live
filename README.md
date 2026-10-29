# LiveHelp

## Server (VPS + domain with HTTPS, Node 20+)
    cd server && npm install
    export JWT_SECRET=$(openssl rand -hex 32)
    export ADMIN_PHONE=8601148025 ADMIN_PASSWORD=raaz86011     # change this to a strong password
    # optional TURN (coturn): TURN_URL, TURN_SECRET
    node index.js
Caddy for HTTPS + WebSocket:  `support.example.com { reverse_proxy localhost:3000 }`

Admin: open https://support.example.com in a browser, log in with the admin number and password.
All users who tapped Start appear live in a grid. Users register inside the app.

## APK without a PC (GitHub Actions)
1. Upload this folder's contents to a new GitHub repository (branch main).
2. Repo > Settings > Secrets and variables > Actions > Variables: add `SERVER_URL` = https://support.example.com
3. Actions tab > Build APK > Run workflow. Download `LiveHelp-apk` > app-debug.apk and install it.

## APK on a PC
    gradle wrapper --gradle-version 8.7
    ./gradlew assembleDebug -PSERVER_URL=https://support.example.com
