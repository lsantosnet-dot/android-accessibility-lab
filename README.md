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

## Como o app decide "qual tela ler"

Apps costumam manter a tela anterior viva atrás da que está em exibição — o
Gmail faz isso: ao abrir uma mensagem, a lista de e-mails continua na árvore
de acessibilidade. Sem cuidado, o leitor acaba lendo a lista, não a mensagem.
`CaptureAccessibilityService` resolve isso em duas etapas:

1. **Escolha da janela** (`findForegroundApp`): entre as janelas de
   aplicativo, ganha a que tem o foco de entrada (o painel flutuante é
   `FLAG_NOT_FOCUSABLE`, então tocar em "ler tela" não rouba esse foco do
   app); em seguida, a que tem maior área realmente visível, calculada
   subtraindo as janelas de camada mais alta; e por fim a ordem Z (`layer`).
   Os limites da janela escolhida viram o *clip* de tudo que é capturado
   dela.
2. **Escolha dos nós** (`collectVisibleContent`): três filtros, em ordem:
   nós com `isVisibleToUser == false` são descartados junto com sua
   subárvore; nós cujos limites nem tocam a área da janela na tela também —
   um painel *deslizado para fora da tela* (o Gmail estaciona a lista de
   e-mails ao lado da mensagem aberta, no layout de dois painéis que ele usa
   até em celular) continua se declarando visível, e limites fora da tela
   nunca são "cobertos" por nada, então o teste de oclusão sozinho não o
   pega — foi por aí que as tentativas anteriores deixaram a caixa de
   entrada vazar para a leitura. Por fim, irmãos são avaliados do mais
   acima para o mais abaixo — pela `drawingOrder` real quando o app a
   informa, senão pela posição entre os irmãos — e um irmão coberto pelos
   de cima que produziram texto é descartado, com tolerância de 3% para a
   tela de trás que aparece por uma fresta. A ordem de leitura em voz alta
   continua normal: só a *decisão* é feita de cima para baixo.

A **rolagem automática** usa essa mesma travessia para escolher *o que*
rolar. Antes ela rolava o primeiro scrollable visível da árvore, que podia
ser a lista escondida atrás da mensagem — o ciclo seguinte capturava as
linhas novas da caixa de entrada e as lia misturadas ao e-mail.

Se nada sobrar desse filtro — alguns apps reportam `isVisibleToUser` errado
nos contêineres —, o serviço cai de volta na leitura da árvore inteira, para
não ficar mudo. Cada nó descartado pelos filtros sai no logcat
(`collect: dropping …`) com o motivo e os limites, para diagnosticar o
próximo app que ler errado.

Na **rolagem automática**, cada ciclo lê só o que ainda não foi lido: uma
rolagem raramente avança uma tela inteira e cabeçalhos não se movem, então
capturas consecutivas se sobrepõem bastante. O serviço guarda os trechos já
falados na sessão e encerra quando uma captura não traz nada novo.

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
