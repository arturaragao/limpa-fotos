# Limpa Fotos

## O que é

App Android pessoal e gratuito de Artur para revisar imagens locais. Repositório
público `arturaragao/limpa-fotos`. Kotlin, applicationId
`io.github.arturaragao.limpafotos`, minSdk 30, compile/targetSdk 35.

## Arquitetura

- `PhotoRules.kt`: modelos e regras puras, lotes sem entradas já analisadas,
  filtro de mantidas por id, exclusão por rosto/rótulos humanos antes das categorias
  e repetidas, classificação conservadora, Laplaciano, Hamming, melhor repetida,
  seleção inicial, MB decimais e máquina de progresso do Google Fotos.
- `PhotoScanner.kt`: MediaStore Images (inclui GIF/WebP), permissões completas
  ou parciais, ImageDecoder até 1600 px para rostos e rótulos, 256 px para
  Laplaciano e dHash de 64 bits. ML Kit image-labeling e face-detection:16.1.7
  embarcados, offline; rosto ou rótulo humano exclui de todas as categorias.
  Cache JSON atômico v3 em filesDir por id + DATE_MODIFIED; v2 não é reaproveitado
  porque não passou por rostos. KeptPhotoStore salva ids em kept-photos.json,
  independente do cache; ReviewSessionStore guarda a fila da prévia sem limite do
  Binder. Nenhum arquivo contém fotografias. Favoritos sempre vêm da consulta
  atual, e falhas de análise são tentadas de novo na próxima varredura.
- `CleanViewModel.kt`: coroutine de varredura fora da UI e StateFlow; sobrevive
  à rotação. Seleção global por id, mantidas persistentes e fila Google Fotos
  restaurável via SavedStateHandle. Confere MediaStore fora da UI ao retornar;
  ausência com acesso completo ou IS_TRASHED confirma exclusão local. Falha de
  consulta ou ausência sob acesso parcial pede decisão, sem contar exclusão.
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
- Nunca pré-marcar favoritos ou a melhor imagem de um grupo de repetidas.
- Dependências de ML autorizadas: image-labeling:17.0.9 e face-detection:16.1.7
  embarcadas, mais coroutines/lifecycle se necessários. Não incluir biblioteca
  de imagem ou UI. A autorização de face-detection foi dada em 24/10/2026.
- Arquivos UTF-8 sem BOM; editar com apply_patch. No PowerShell usar
  [IO.File]::WriteAllText se necessário, nunca Set-Content/Out-File.
- Responder em português, até 25 linhas; conclusão com arquivos/riscos até 20 linhas.

## Estado atual

Versão padrão 0.1.2 / code 2. Mantidas persistentes desaparecem de todas as
categorias e lotes; Configurações → Mostrar de novo as que mantive limpa a lista.
Analisar mais já pulava o cache válido e continua assim, com novos testes.
Categorias: prints, documentos/papel (rótulo >= 0,90), figurinhas (WebP pequeno
e quadrado até 512 px ou pasta WhatsApp Stickers), lixo do WhatsApp Images
(evidência gráfica >= 0,95; não basta a pasta, exclui Sent), muito borradas
(variância < 15 em 256 px) e repetidas sem pessoas. Comida removida. Rostos
detectados ou rótulos humanos >= 0,40 excluem inclusive prints e repetidas;
falhas de ML não geram sugestões. Favoritos, Koda e melhor repetida protegidos.
Fluxo Google Fotos e prévia em tela cheia implementados; validar em aparelho
confirmação local/nuvem, retorno, pular/repetir, fallback, rotação e acesso parcial.
Detecção não garante encontrar todos os rostos (pequenos, ocultos, perfis e
animações); revisão humana continua necessária. O modelo aumenta o APK e o
custo da análise. Cache v2 será reanalisado manualmente em novos lotes.

Lotes configuráveis (padrão 200 salvo em SharedPreferences), continuidade pelo cache e progresso X/Y implementados.
Engrenagem com limite, permissões e versão; resumo visual renovado. Novos testes JVM escritos; execução local bloqueada pelo gradle-wrapper.jar ausente.

Implementação inicial escrita em 24/10/2026. Testes JVM em app/src/test cobrem
Hamming, agrupamento e limites, favoritos, metadados, Laplaciano/limiar, MB e
UpdateVersion. Ambiente informado tem só Java 8 e não tem SDK: o build Android
e os testes Gradle dependem do CI e ainda não foram executados aqui.

Pendência de requisito conflitante: solicitado RecyclerView, mas ele não está
no modelo, e novas dependências de UI foram proibidas. Foi perguntada autorização;
enquanto não houver resposta, usa-se GridView nativa com reciclagem de views.
Visual usa Theme.Material nativo, cores claras/escuras e cartões arredondados.

Revisar em aparelho: permissões Android 11/13/14+ (incluindo acesso parcial),
seleção, confirmação/cancelamento/restauração da lixeira, desempenho na primeira
varredura e cache na segunda, falsos positivos, widget e instalação da atualização.
Intervalos de workers são aproximados. Restrição de rede precisa acompanhar
eventuais mudanças nos hosts de download do GitHub.

## Histórico

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
