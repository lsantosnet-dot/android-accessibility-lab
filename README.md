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
capture/CaptureTuning           → as duas chaves que decidem qual árvore é capturada (persistidas)
capture/TreeDumper              → grava a árvore completa de todas as janelas num .txt em Downloads
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

Esta seção é o resultado de um diagnóstico feito em cima de dumps reais da
árvore de acessibilidade (veja "Como diagnosticar" abaixo), depois de várias
correções feitas no escuro que erraram o alvo.

### O que os dumps mostraram

Numa mensagem aberta do Gmail, com a caixa de entrada supostamente "viva atrás
da tela":

- **A caixa de entrada não estava lá.** `#conversation_list_place_holder` vem
  com `children=0`. A janela é uma só, e é a da conversa. As tentativas
  anteriores estavam caçando um fantasma.
- **Todo o conteúdo de WebView é `isImportantForAccessibility=false`.** O
  Chromium reporta assim cada nó do corpo do e-mail. Isso torna
  `flagIncludeNotImportantViews` **obrigatória**: sem ela o sistema poda a
  árvore inteira e o mesmo e-mail que rende 680 caracteres de texto vira
  `WebView #conversation_webview … children=0`. O TalkBack passa sem a flag
  porque nunca lê uma tela em bloco — ele fala o nó sob o dedo, e o Chromium
  responde a essa consulta pontual.
- **O que rolava era um pager.** A conversa mora num
  `androidx.viewpager.widget.ViewPager #item_pager`, que segura as mensagens
  *vizinhas*. Como o auto-scroll pegava o scrollable mais raso, o alvo era o
  pager — e `ACTION_SCROLL_FORWARD` nele **passa para o próximo e-mail**. Era
  isso que fazia a leitura contínua ler uma mensagem, depois outra, com
  cabeçalho e tudo, parecendo estar presa em loop.
- **A maior parte do que era falado não era o e-mail.** Dos 29 segmentos
  capturados, 18 eram cromo: `"Eduardo Bolsonaro condenado Caixa de entrada"`
  (o nó do assunto termina com o nome da pasta — daí a sensação de "ele lê a
  tela principal do Gmail"), "Navegar para cima", "Gemini", "Arquivar",
  "Excluir", "Marcar como não lida", "E-mail, 6 novas notificações",
  "Reunião". Os 11 restantes eram a mensagem.
- **E o nome de um container é calculado a partir do conteúdo — e truncado.**
  Numa newsletter (O Globo/Sonar), o Chromium entregou uma cadeia de
  `android.view.View` aninhados em que o texto se acumula descendo: o mais
  externo dizia só `"Facebook Twitter Instagram YouTube"` (34 caracteres) e a
  carta inteira estava abaixo dele. Como a regra antiga era "nó com texto
  próprio fala pela subárvore inteira", a captura parava ali: **34 caracteres
  de um e-mail de 700**, e o `pickMainContent` descartava o WebView por não
  atingir o mínimo, caindo na janela inteira — que é só cromo. Por isso a
  decisão entre o texto do pai e o dos filhos passou a ser tomada *depois* de
  percorrer a subárvore, comparando os dois (`saysEverythingIn` /
  `isEchoedBy`), e a cobertura exigida do pai é total, não "a maior parte":
  aceitar 60% descartava silenciosamente o resto da carta.

- **Descrições que repetem os filhos.** Um container do Chromium vinha com
  `contentDescription` = "Dino e Carmen Lúcia votaram… Eduardo não irá
  automaticamente p... Ler mais »", e seus três `TextView` filhos carregavam
  exatamente esses três pedaços. O parágrafo era falado duas vezes, uma
  inteiro e uma em partes.

### O que o app faz com isso

**Escolha da janela** (`findForegroundApp`): entre as janelas de aplicativo,
ganha a que tem o foco de entrada (o painel flutuante é `FLAG_NOT_FOCUSABLE`,
então tocar em "ler tela" não rouba esse foco do app); depois a de maior área
realmente visível, calculada subtraindo as janelas de camada mais alta; e por
fim a ordem Z. Os limites da janela viram o *clip* da captura.

**Escolha do conteúdo** (`pickMainContent`): entre os scrollables que
sobreviveram à travessia, pagers são excluídos de saída — rolar um troca de
página, não de posição —; entre os que restam, ganha quem anuncia uma ação de
rolagem *vertical* (`ACTION_SCROLL_DOWN`) e, entre esses, quem tem mais texto.
Abaixo de 80 caracteres nada se qualifica, e a leitura volta a ser da janela
inteira. **O mesmo nó escolhido é o que se lê e o que se rola** — era a
divergência entre os dois que produzia o efeito de loop.

**Filtros da travessia** (`collectVisibleContent`): nós com
`isVisibleToUser == false` saem com sua subárvore; nós cujos limites não tocam
a área da janela também — o pager do Gmail estaciona as mensagens vizinhas
logo além da borda direita, em limites como `[795,64][1486,1290]` numa tela de
720px, e elas continuam se declarando visíveis. O terceiro filtro, a oclusão
por pixels pintados, vem **desligado**: é o que uma vez apagou o corpo inteiro
de um e-mail, confundindo um cabeçalho nativo flutuante com cobertura.

**Contra repetições**: cada nó decide, depois de percorrer sua subárvore, se
quem lê melhor o mesmo conteúdo é ele ou os filhos — pai quando ele diz tudo
que os filhos dizem (`saysEverythingIn`, senão a frase sai picada nos spans que
a compõem), filhos quando eles dizem mais que ele (`isEchoedBy`). Dentro de uma
mesma captura, um trecho **contido** em outro já falado é eco ou moldura, não
conteúdo, e sai. Segmentos que são um bloco impronunciável — um token de
rastreamento, uma URL codificada, qualquer "palavra" de mais de 40 caracteres —
também saem.

Se nada sobrar dos filtros, o serviço cai de volta na leitura da árvore
inteira, para não ficar mudo. Cada nó descartado sai no logcat
(`collect: dropping …`) com o motivo e os limites.

O serviço se declara `feedbackSpoken` e `isAccessibilityTool` no XML — não é
cosmético: WebViews baseadas em Chromium entregam uma árvore reduzida (só
controles interativos, sem texto estático) a serviços que elas não classificam
como leitores de tela.

As três decisões acima são chaves na tela do app (`CaptureTuning`), aplicadas
ao vivo, para poder comparar comportamentos no aparelho sem recompilar.

Na **rolagem automática**, cada ciclo lê só o que ainda não foi lido: uma
rolagem raramente avança uma tela inteira e cabeçalhos não se movem, então
capturas consecutivas se sobrepõem bastante. O serviço guarda os trechos já
falados na sessão e encerra quando uma captura não traz nada novo. A
comparação não é literal: WebViews reagrupam seus nós conforme o conteúdo
rola, e o mesmo texto volta fundido com o vizinho — um trecho também conta
como lido quando aparece dentro de algo já falado, ou quando algo já falado
compõe a maior parte dele. A captura pós-rolagem espera 1,2s: os nós da
WebView reportam os limites antigos por um tempo depois que a rolagem assenta.

## Como diagnosticar um app que lê errado

O botão **🧪 dump** no painel flutuante grava em `Downloads/` a árvore de
acessibilidade completa de **todas** as janelas da tela que estiver aberta,
com os campos que decidem tudo: `vis` (`isVisibleToUser`), `IMP`
(`isImportantForAccessibility`), `bounds`, `drawingOrder`, `paneTitle`,
`CLICKABLE`, e as ações de rolagem que cada scrollable oferece
(`SCROLLABLE/DOWN`, `/RIGHT`, `/FWD` — um pager oferece `/FWD` e `/RIGHT` mas
não `/DOWN`). O cabeçalho registra em que modo o dump foi tirado e qual janela
o `findForegroundApp` escolheu. Nenhum `adb` necessário.

O dump precisa ser tirado **por cima do app problemático** — o botão mora no
painel flutuante justamente porque abrir a tela de configurações trocaria a
tela que interessa. Dois dumps da mesma tela, um em cada modo, mostram o que
cada chave muda.

Os dumps ficam versionados fora do git, em `captures/`.

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
