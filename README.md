# Accessibility Lab

Protótipo de estudo sobre a `AccessibilityService` do Android. Ao ativar o
serviço e conceder a permissão de overlay, um painel flutuante compacto
permite ler em voz alta o conteúdo de **qualquer app** aberto no aparelho —
o objetivo é aprender como leitores de tela enxergam a tela por baixo dos
panos.

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
5. Com as duas permissões ativas, toque em **"Abrir janela flutuante"** na
   própria tela do app (ou apenas volte para a tela inicial de qualquer
   app: o painel também aparece automaticamente).

A tela do app também reúne os controles de **velocidade e tom de voz** da
leitura — eles ficam na configuração, não no painel flutuante, para manter o
painel pequeno.

## Arquitetura

```
MainActivity                    → onboarding + status + configurações (Compose)
capture/CaptureAccessibilityService → escuta os eventos, lê a árvore de nós
capture/CaptureBus              → StateFlow em memória, estado de rolagem automática
overlay/OverlayManager          → painel flutuante compacto (WindowManager + Views)
reader/ScreenReader             → TTS + detecção de idioma
reader/SpeechPrefs              → velocidade/tom persistidos, lidos pelo serviço e pela tela de configurações
reader/MediaSessionController   → MediaSessionCompat + notificação MediaStyle: card de play/pause e avançar/voltar na tela de bloqueio
reader/MediaControlReceiver     → recebe os toques nesse card e repassa para o serviço
res/xml/accessibility_service_config.xml → declara os tipos de evento ouvidos
```

O card na tela de bloqueio é a mesma superfície nativa que apps de música usam (uma
`MediaSessionCompat` associada a uma notificação `MediaStyle`) — não um overlay: janelas
`TYPE_APPLICATION_OVERLAY` nunca são desenhadas sobre a tela de bloqueio segura. Ele só aparece
enquanto a leitura em voz alta está ativa (tocando ou pausada), e exige a permissão de
notificação (Android 13+), pedida na própria tela do app.

`CaptureAccessibilityService` expõe uma instância estática (`instance`) para
que `MainActivity` possa abrir o painel flutuante (`openOverlay()`) e
ajustar velocidade/tom (`adjustRate()`/`adjustPitch()`) mesmo sem esperar um
evento de acessibilidade — antes disso, era preciso desativar e reativar o
serviço para o painel reaparecer depois de conceder a permissão de overlay.

## Privacidade

- Nada é persistido em disco além da velocidade/tom de voz escolhidos.
- Campos marcados como senha (`node.isPassword`) têm o texto substituído por
  `••••••` antes de ser lido em voz alta — o mesmo padrão que leitores de
  tela reais usam.
- Como o serviço não tem `packageNames` restrito no config XML, ele recebe
  eventos de **qualquer app em primeiro plano**, incluindo apps de
  terceiros. Use apenas no seu próprio aparelho, para fins de estudo.

## Próximos passos (fora do escopo deste protótipo)

- Persistência opcional (Room) para revisar capturas depois.
- Filtro por pacote/app.
- Throttling de eventos de alta frequência.
- Testes automatizados do parsing da árvore de nós.
