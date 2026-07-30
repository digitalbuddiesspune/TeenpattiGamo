# Teen Patti Client

Next.js app-router frontend for the Teen Patti casino UI.

## Environment

Set the backend API base with:

```env
NEXT_PUBLIC_API_BASE=http://localhost:4100/api
```

If the variable is omitted, the code falls back to `http://localhost:4000/api`.

## Getting Started

Install dependencies and run the development server:

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser.

The UI talks to the backend through REST under `/api` and native WebSockets at `/ws/public-tables` and `/ws/private-rooms`.

## Scripts

```bash
npm run dev
npm run lint
npm run build
```

## Static Production Deployment

This app is configured for Next static export. Production builds are emitted to:

```text
client/out/
```

For the single-EC2 setup, build locally and upload only the static output:

```bash
export EC2_IP="YOUR_EC2_PUBLIC_IP"
export EC2_USER="ubuntu"
export PEM="$HOME/path/to/your-key.pem"
export PROJECT="$HOME/Desktop/craftProjects/gd/Teenpatti/TeenPattiMain"

cd "$PROJECT/client"
cat > .env.production <<EOF
NEXT_PUBLIC_API_BASE=http://$EC2_IP/api
EOF

npm ci
NODE_OPTIONS="--max-old-space-size=1024" npm run build

ssh -i "$PEM" "$EC2_USER@$EC2_IP" \
  "sudo mkdir -p /var/www/teenpatti && sudo chown -R ubuntu:ubuntu /var/www/teenpatti"

rsync -azP --delete \
  -e "ssh -i $PEM" \
  "$PROJECT/client/out/" \
  "$EC2_USER@$EC2_IP:/var/www/teenpatti/"
```

Nginx should serve `/var/www/teenpatti` as static files and proxy `/api/`, `/ws/`, and `/management/` to the backend on `127.0.0.1:4100`.

Static table routes use query parameters:

- Public table: `/public?variant=classic`
- Private room: `/private?roomCode=ABCDEF`

## Game Modes

- Public tables: `classic`, `ak47`, `muflis`, `flipper`, and `jhandu`
- Private rooms: host-created rooms using the same variant set, with host-controlled boot amount and round start

Public sessions are stored per browser window in `sessionStorage`. Private-room sessions are stored in `localStorage` using the room code.

## Main Paths

- `app/page.js`: app-router entry point
- `app/components/GameClient.js`: main game shell
- `app/hooks/useTeenPattiGame.js`: public table state and socket flow
- `app/hooks/usePrivateRoomGame.js`: private room state and socket flow
- `app/lib/api.js`: REST and WebSocket URL helpers

See the repository-level documentation for full gameplay and backend contracts.
For production setup, see the [Deployment Document](../docs/TEEN_PATTI_DEPLOYMENT_DOCUMENT.md).
