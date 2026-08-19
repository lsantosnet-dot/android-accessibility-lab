# Accessibility Lab

Protótipo de estudo sobre a `AccessibilityService` do Android. Ao ativar o
serviço e conceder a permissão de overlay, um painel flutuante mostra em
tempo real os eventos de acessibilidade (troca de janela, mudança de
conteúdo, cliques, foco...) capturados de **qualquer app** aberto no
aparelho — o objetivo é aprender como leitores de tela e ferramentas de
automação enxergam a tela por baixo dos panos.

## Como rodar

1. Abra a pasta no Android Studio (Hedgehog ou mais recente). Se ele avisar
   que o Gradle wrapper está incompleto, deixe o próprio Android Studio
   regenerar o `gradle-wrapper.jar` (ou rode `gradle wrapper --gradle-version 8.7`
   localmente se tiver o Gradle instalado).
2. Rode o módulo `app` num dispositivo/emulador com Android 8.0 (API 26) ou
   superior.
3. No app, toque em **"Abrir configurações de acessibilidade"** e ative o
   serviço "Accessibility Lab — Capture Service" manualmente — o Android não
   permite que um app se autoconceda essa permissão.
4. Toque em **"Conceder permissão de overlay"** e autorize o app a desenhar
   sobre outros apps.
5. Volte para a tela inicial de qualquer app: o painel flutuante deve
   aparecer no canto superior esquerdo, com o log de eventos ao vivo.

## Arquitetura

```
MainActivity                    → onboarding + status (Compose)
capture/CaptureAccessibilityService → escuta os eventos, lê a árvore de nós
capture/CaptureEvent            → modelo de dado de um evento capturado
capture/CaptureBus              → StateFlow em memória, ponte serviço → overlay
overlay/OverlayManager          → painel flutuante (WindowManager + Views)
res/xml/accessibility_service_config.xml → declara os tipos de evento ouvidos
```

Fluxo: `CaptureAccessibilityService.onAccessibilityEvent()` extrai dados do
nó de origem e publica em `CaptureBus`. `OverlayManager` observa esse
`StateFlow` e redesenha a lista de eventos no painel flutuante.

## Privacidade

- Nada é persistido em disco — os eventos vivem só em memória (até 50 no
  buffer) e somem quando o serviço para.
- Campos marcados como senha (`node.isPassword`) têm o texto substituído por
  `••••••` antes de chegar ao overlay ou a qualquer log — o mesmo padrão que
  leitores de tela reais usam.
- Como o serviço não tem `packageNames` restrito no config XML, ele recebe
  eventos de **qualquer app em primeiro plano**, incluindo apps de
  terceiros. Use apenas no seu próprio aparelho, para fins de estudo.

## Próximos passos (fora do escopo deste protótipo)

- Persistência opcional (Room) para revisar capturas depois.
- Filtro por pacote/app.
- Throttling de eventos de alta frequência.
- Testes automatizados do parsing da árvore de nós.
