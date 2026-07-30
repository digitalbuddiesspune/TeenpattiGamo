# Teen Patti Casino

This repository contains the current Teen Patti implementation plus some historical server directories. The active stack documented here is:

- `client`: Next.js casino UI
- `serverJavaNew`: Kotlin/Spring Boot backend with MongoDB and Redis

## Documentation

- [Functional Document](docs/TEEN_PATTI_FUNCTIONAL_DOCUMENT.md)
- [Technical Document](docs/TEEN_PATTI_TECHNICAL_DOCUMENT.md)
- [Deployment Document](docs/TEEN_PATTI_DEPLOYMENT_DOCUMENT.md)
- [Backend README](serverJavaNew/README.md)

## Local Run

Backend:

```bash
cd serverJavaNew
cp .env.example .env
./gradlew bootRun
```

Frontend:

```bash
cd client
npm install
npm run dev
```

## Validation

Frontend:

```bash
cd client
npm run lint
npm run build
```

Backend:

```bash
cd serverJavaNew
./gradlew test
```

## Notes

- Public tables and private rooms support `classic`, `ak47`, `muflis`, `flipper`, and `jhandu`.
- Private rooms are host-controlled. The host chooses the variant and boot amount when creating the room, and can update those settings while the room is still in the lobby.
- The backend is authoritative for gameplay, timers, settlement, persistence, and reconnect handling.
- See the technical document for REST APIs, WebSocket protocols, runtime architecture, and environment configuration.
