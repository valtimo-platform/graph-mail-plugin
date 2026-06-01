# Getting Started

## Prerequisites

- Java 17+
- Node.js >= 20, < 23
- Valtimo 13.x
- Azure App Registration met de applicatiemachtigingen:
  - `Mail.Send` — vereist voor alle e-mailverzendingen
  - `Mail.ReadWrite` — vereist voor bijlagen groter dan 3 MB

## Plugin development

The plugin source code is located in:

- Backend: `backend/plugin/src/`
- Frontend: `frontend/projects/plugin/src/`

For more information on how to build a plugin, see
the [Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition) documentation.

## Build

### Backend

All commands below should be run from the `backend` directory.

```shell
cd backend
./gradlew build
```

Tests uitvoeren:

```shell
./gradlew test
```

### Frontend

```shell
cd frontend
npm install
npm run build
```

## Installatie in je Valtimo-project

### Backend

Voeg de volgende dependency toe aan je `build.gradle.kts`:

```kotlin
implementation("com.ritense.valtimoplugins:graph-mail:1.0.0")
```

Voeg de volgende configuratie toe aan je `application.yml`:

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

> **Verplicht:** zonder voldoende job-executor threads kan de applicatie vastlopen als de Graph API rate-limiteert. Zie [Plugin Documentatie](plugin.md) voor details.

### Frontend

```shell
npm install @valtimo-plugins/graph-mail
```

Voeg de module en specificatie toe aan je `AppModule`:

```typescript
import { NgModule } from '@angular/core';
import { PLUGINS_TOKEN } from '@valtimo/plugin';
import { GraphMailPluginModule, graphMailPluginSpecification } from '@valtimo-plugins/graph-mail';

@NgModule({
  imports: [
    // ... andere imports
    GraphMailPluginModule,
  ],
  providers: [
    { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification] },
  ],
})
export class AppModule {}
```

> Als je meerdere plugins registreert, combineer je de `useValue`-arrays of gebruik je `multi: true`:
> ```typescript
> { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification, anderePluginSpecification] }
> ```
