# Limpar Fotos

## O que é

App Android pessoal e gratuito de Artur para revisar imagens locais. Repositório
público `arturaragao/limpa-fotos`. Kotlin, applicationId
`io.github.arturaragao.limpafotos`, minSdk 30, compile/targetSdk 35.

## Arquitetura

- `PhotoRules.kt`: modelos e regras puras, lotes sem entradas já analisadas,
  filtro de mantidas por id, proteção de identificação pessoal, favoritos e Koda;
  rostos/rótulos humanos excluem fotos comuns, mas não prints/figurinhas/GIFs.
  Categorias, Laplaciano, Hamming, melhor repetida,
  seleção inicial, MB decimais e máquina de progresso do Google Fotos.
- `PhotoScanner.kt`: MediaStore Images (inclui GIF/WebP), permissões completas
  ou parciais, ImageDecoder até 1600 px para rostos e rótulos, 256 px para
  Laplaciano e dHash de 64 bits. ML Kit image-labeling, face-detection:16.1.7
  e text-recognition:16.0.1 latino embarcados, offline. Sempre extrai rostos,
  rótulos (limiar 0,1; índice/confiança) e OCR (somente contagens/flag de identificação persistidas).
  Cache JSON atômico v5 (nome analysis-v3.json) em filesDir por id + DATE_MODIFIED;
  v3/v4 são descartados integralmente, com aviso "Análise refeita para aprender".
  KeptPhotoStore salva ids em kept-photos.json,
  independente do cache; ReviewSessionStore guarda a fila da prévia sem limite do
  Binder. Nenhum arquivo contém fotografias. Favoritos sempre vêm da consulta
  atual, e falhas de análise são tentadas de novo na próxima varredura.
- `Learner.kt`: Kotlin puro, 528 características fixas (16 gerais + 512 rótulos),
  regressão logística L2 1e-3, classes balanceadas, 300 épocas. Mistura modelo/prior
  com n/(n+20); exige pelo menos 3 exemplos de cada classe. Categoria "Pelo seu jeito".
- `TrainingStore.kt`: training.json atômico, até 5000 ids/exemplos mais recentes,
  características preservadas após apagar a foto, serializadas de forma esparsa;
  model.json atômico. LearningEngine vive no processo, retreina em Dispatchers.Default
  após debounce de 1 segundo, publica revisão e reavalia sugestões. Arquivo local
  pending-deletion-training.json conserva características durante a confirmação externa.
- `ScanWorker.kt`: análise em segundo plano via WorkManager, trabalho único `scan`
  com KEEP e serviço em primeiro plano dataSync, notificação de progresso e resultado.
  Cache por foto permite retomar após interrupção; negar notificações não impede análise.
- `CleanViewModel.kt`: observa o worker e reconecta o progresso ao reabrir; leitura
  só do cache em coroutine local. Seleção global por id, mantidas persistentes e fila Google Fotos
  restaurável via SavedStateHandle. Confere MediaStore fora da UI ao retornar;
  ausência com acesso completo ou IS_TRASHED confirma exclusão local. Falha de
  consulta ou ausência sob acesso parcial pede decisão, sem contar exclusão.
  Toda marcação/desmarcação da grade/prévia e exclusão confirmada gera exemplo.
  Modo Ensinar: até 60 já analisadas, 30 incertas + 30 aleatórias, sem pré-seleção;
  Pronto ensina todas, mantém as não marcadas e oferece Mais 60. Fotos escolhidas
  explicitamente para apagar entram nas sugestões mesmo antes do mínimo de classes.
- `MainActivity.kt` / `PhotoAdapter.kt`: chips com contagem/MB, grade reciclada
  nativa com loadThumbnail, seleção pelo ✓, toque abre a prévia. Ação principal
  abre cada content URI no pacote Google Fotos com permissão temporária; Artur
  confirma a lixeira lá. Ao retornar, avança automaticamente se excluída, senão
  oferece Pular/Tentar de novo; progresso N de M, contagem e Encerrar. Sem Google
  Fotos, usa createTrashRequest; lixeira só do celular também é opção secundária.
  A API não confirma exclusão na nuvem: depende da ação escolhida no Google Fotos.
- `PhotoActivity.kt`: tela cheia sem barras, pinça/duplo toque para zoom e arraste
  da imagem ampliada. Deslizar sem zoom navega na mesma categoria. Botão salva
  manter/marcar imediatamente; mantidas são puladas nas próximas navegações.
  GIF/WebP animados quando suportados; sessão local sobrevive à rotação.
- `Background.kt`: widget 1×1 e lembrete periódico de 30 dias sem ML; WorkManager
  pode adiar a execução. Lembrete ignora favoritos e deduplica categorias por id.
- `Updater.kt` / `UpdateVersion.kt`: adaptação do modelo banco-cbr-android,
  API Releases latest deste repo, verificação ao abrir limitada a 1/min e worker
  periódico de 15 min. FileProvider expõe só cache/update. Android confirma instalação.
- `res/xml/network_security_config.xml`: sem HTTP, HTTPS só nos destinos GitHub
  necessários ao atualizador. Diagnósticos externos do SDK ficam bloqueados.
- `.github/workflows/apk.yml`: Java 17, SDK 35, wrapper gerado sem JAR no repo,
  JUnit + lint + assembleRelease; secrets existentes, alias `fotos`, sem fallback
  para debug. Tag v0.1.N / versionName 0.1.N / asset limpa-fotos.apk.

## Pedidos do Artur

- Trabalhar só neste projeto e ler apenas os arquivos indicados do modelo
  `C:\Users\artur.aragao\Documents\banco-cbr-android`; nunca alterá-lo.
- Não fazer commit/push. Não ler/gravar outros repositórios para esta tarefa.
- Tudo no aparelho, sem fotos na rede, tokens, Firebase ou serviço pago.
- Não apagar imagens diretamente; só lixeira mediante confirmação do Android.
  Mover para a pasta do lote (createWriteRequest + RELATIVE_PATH) autorizado em 25/10/2026.
- Nunca pré-marcar favoritos ou a melhor imagem de um grupo de repetidas.
- Dependências de ML (desde 25/10/2026 as versões play-services-mlkit-*, não embarcadas): image-labeling:17.0.9 e face-detection:16.1.7
  e text-recognition:16.0.1 (latino) embarcadas, mais coroutines/lifecycle se necessários.
  Nenhuma outra dependência nova. Não incluir biblioteca de imagem ou UI.
  Face-detection e text-recognition autorizados em 24/10/2026.
- Sempre sugerir prints (inclusive com rosto), figurinhas, GIFs e documentos/
  contas/papéis (rótulo atual ou pelo menos 80 letras/dígitos reconhecidos).
  Prints/figurinhas/GIFs nunca são excluídos por rosto/rótulo de pessoa.
- Nunca sugerir CNH, RG, passaporte, cartão CPF, carteira de trabalho, título
  eleitoral, certidões ou carteira do CRM. Contas/boletos/recibos/comprovantes podem;
  CPF sozinho num boleto não identifica cartão CPF. Favoritos e Koda (cat) nunca.
- Fotos de câmera com rostos/selfies/pessoas continuam fora da grade e do ensino.
- Aprender sempre com cada decisão no aparelho, offline, sem rede ou serviço pago.
- Arquivos UTF-8 sem BOM; editar com apply_patch. No PowerShell usar
  [IO.File]::WriteAllText se necessário, nunca Set-Content/Out-File.
- Responder em português, até 25 linhas; conclusão com arquivos/riscos até 20 linhas.

## Estado atual

Versão padrão 0.1.3 / code 3. Nome visível: Limpar Fotos. Análise em segundo plano
com WorkManager em primeiro plano (notificação), reconexão do progresso e cancelamento
com recarga do cache. Permissão de notificações pedida antes da primeira análise no
Android 13+; negar não bloqueia. Relatório e avisos do worker preservados no resumo.
Mantidas persistentes desaparecem de todas as
categorias e lotes; Configurações → Mostrar de novo as que mantive limpa a lista.
Analisar mais já pulava o cache válido e continua assim, com novos testes.
Categorias: prints, documentos/papel (rótulo >= 0,90 ou OCR >= 80 letras/dígitos),
figurinhas (WebP pequeno e quadrado até 512 px ou pasta WhatsApp Stickers), GIFs,
lixo do WhatsApp Images, muito borradas, repetidas e "Pelo seu jeito".
As categorias antigas continuam descritivas; fora das regras obrigatórias,
o limiar é p >= 0,5 (prior 0,5 para WhatsApp, 0,15 para demais, 0,95 obrigatórias).
Comida não sugere. Rostos/rótulos de pessoa >= 0,60 em fotos comuns ficam fora;
prints/figurinhas/GIFs ignoram esses filtros. Identificação pessoal, favoritos e
Koda protegidos sempre. Melhor repetida não é pré-marcada pelo modelo; decisão
explícita do Artur e regras obrigatórias prevalecem. Mantidas não voltam.
Fluxo Google Fotos e prévia em tela cheia implementados; validar em aparelho
confirmação local/nuvem, retorno, pular/repetir, fallback, rotação e acesso parcial.
Detecção não garante encontrar todos os rostos (pequenos, ocultos, perfis e
animações); revisão humana continua necessária. O modelo aumenta o APK e o
custo da análise; OCR aumenta o APK/tempo e pode falhar em texto pequeno,
desfocado, cortado ou em outro alfabeto. Cache antigo será reanalisado em novos lotes.
Ensinar usa até 60 elegíveis ainda sem exemplo; se houver menos, usa as disponíveis.
Resumo mostra "Aprendizado: N exemplos". Pronto salva o lote e aguarda o treino;
marcadas aparecem nas sugestões, demais viram mantidas persistentes.

Lotes configuráveis (padrão 200 salvo em SharedPreferences), continuidade pelo cache e progresso X/Y implementados.
Engrenagem com limite, permissões e versão; resumo visual renovado. Novos testes JVM escritos; execução local bloqueada pelo gradle-wrapper.jar ausente.

Implementação inicial escrita em 24/10/2026. Testes JVM em app/src/test cobrem
Hamming, agrupamento e limites, favoritos, metadados, Laplaciano/limiar, MB e
UpdateVersion. Ambiente informado tem só Java 8 e não tem SDK: o build Android
e os testes Gradle dependem do CI e ainda não foram executados aqui.

Grade em GridView nativa com reciclagem de views, respeitando a proibição de
novas bibliotecas de UI/imagem. Visual usa Theme.Material nativo, cores
claras/escuras e cartões arredondados.

Revisar em aparelho: permissões Android 11/13/14+ (incluindo acesso parcial),
seleção, confirmação/cancelamento/restauração da lixeira, desempenho na primeira
varredura e cache na segunda, falsos positivos, widget e instalação da atualização.
Intervalos de workers são aproximados. Restrição de rede precisa acompanhar
eventuais mudanças nos hosts de download do GitHub.

## Histórico

- 25/10/2026 (lote, APK leve, sem repetidas): ML Kit trocado pelas versões do Google
  Play Services (play-services-mlkit-*; modelos baixados uma vez pelo celular via
  meta-data DEPENDENCIES ica,face,ocr; offline depois) porque o APK de 68 MB deixava a
  atualização lenta. Repetidas desligadas (PhotoRules.SUGGEST_DUPLICATES = false, pedido
  do Artur). Novo botão principal "Apagar N no Google Fotos": createWriteRequest (1
  confirmação) move tudo para Pictures/Apagar - Limpar Fotos/; no Google Fotos ele abre
  a pasta, seleciona tudo e manda para a lixeira (apaga celular + nuvem). Fotos nessa
  pasta não são sugeridas de novo; exemplos de treino confirmam quando somem do
  MediaStore (onResume). Um por um e lixeira só do celular viraram opções secundárias.

- 24/10/2026 (aprendizado offline): adicionados OCR latino embarcado autorizado,
  cache v5 com características completas, identificação pessoal protegida e regras
  fixas para prints/figurinhas/GIFs/documentos. Learner puro com regressão logística,
  histórico atômico de até 5000 exemplos e modelo local; cada decisão da grade,
  prévia ou exclusão confirmada ensina e reavalia. Modo Ensinar (até 60, incertas/
  aleatórias, Pronto/Mais 60), categoria Pelo seu jeito e contador implementados.
  Testes JVM adicionados/ajustados; revisão estática e diff --check. Execução local
  do Gradle bloqueada pelo wrapper JAR ausente; JUnit/build aguardam CI/aparelho.
  Sem mudanças em Updater.kt, ScanWorker.kt ou Background.kt, sem commit/push/rede.

- 24/10/2026 (noite 9): 400 fotos sem nenhuma sugestão. Causa: rótulos de partes do corpo (mão, pele, cabelo, sorriso)
  a 0,40 e qualquer rosto excluíam quase tudo, inclusive prints com avatar. Agora: rótulo de pessoa só os de gente, a 0,60;
  print/figurinha só sai com rosto >= 20% da largura; cache v4 refaz do v3 só as entradas afetadas; mensagem final mostra
  analisadas/sugeridas por categoria/puladas por pessoas. Testes de regressão em PhotoRulesTest.

- 24/10/2026: 0.1.3 / code 3; nome Limpar Fotos, análise em segundo plano com
  WorkManager em primeiro plano (notificação dataSync), progresso reconectável,
  cancelamento e recarga do cache, permissão de notificações antes de analisar.
  Relatório, falhas de leitura e aviso de cache passam pelo outputData do worker.
  Revisão estática e diff --check feitos; compilação local bloqueada pelo wrapper JAR
  ausente. Validar reabertura, cancelamento e permissão negada no aparelho.
  Sem alterações em PhotoRules.kt/PhotoScanner.kt, dependências, commit ou push.
- 24/10/2026: 0.1.2 / code 2; mantidas persistentes e reset, testes de lotes,
  tela cheia com zoom/navegação/seleção, filtros conservadores e remoção de comida,
  face-detection embarcado autorizado, cache v3 e fluxo Google Fotos com progresso,
  verificação no retorno, pular/repetir e fallback confirmado do Android.
  Revisão estática local; JUnit, lint e build aguardam CI (sem SDK/wrapper JAR local).
  Nenhum commit/push nem envio de fotos pela análise.
- 24/10/2026: análise incremental com cache por foto, Configurações e ajustes visuais; sem mudanças de versão, dependências ou commits.
- 24/10/2026: app criado do zero, adaptando build, wrapper, assinatura, workflow,
  atualizador e FileProvider dos arquivos indicados no projeto de referência.
  Nenhum commit ou push realizado.

## Próximo passo
- 24/10/2026 (PC de casa): ramo wip-google-fotos revisado pelo Claude (9 itens do pedido feitos), CI de verificação
  (`verificar.yml`, ramos fora do main, sem publicar) verde, juntado no main → APK 0.3.N.
- Falta testar no celular: Google Fotos abre a foto pelo app com a lixeira dele? Ao voltar, conta como apagada e
  abre a próxima? Pular/Tentar de novo/Encerrar; tela cheia (zoom, deslizar); fotos com pessoas não aparecem;
  mantidas não voltam. Se o Google Fotos abrir a foto sem lixeira, plano B: abrir a foto pelo ID dele não é
  possível pela API, então avaliar "Liberar espaço"/lixeira local + aviso.
