# Limpa Fotos

## O que é

App Android pessoal e gratuito de Artur para revisar imagens locais. Repositório
público `arturaragao/limpa-fotos`. Kotlin, applicationId
`io.github.arturaragao.limpafotos`, minSdk 30, compile/targetSdk 35.

## Arquitetura

- `PhotoRules.kt`: modelos e regras puras, Laplaciano, Hamming, agrupamento sem
  transitividade perigosa, melhor repetida, seleção inicial e MB decimais.
- `PhotoScanner.kt`: MediaStore Images (inclui GIF/WebP), permissões completas
  ou parciais, ImageDecoder até 256 px, dHash de 64 bits e ML Kit embarcado.
  Food exige confiança >= 0,7. Cache JSON atômico versionado em filesDir por
  id + DATE_MODIFIED, sem armazenar fotografias. Favoritos sempre vêm da consulta
  atual, e falhas de análise são tentadas de novo na próxima varredura.
- `CleanViewModel.kt`: coroutine de varredura fora da UI e StateFlow; sobrevive
  à rotação. Seleção global por id evita duplicar contagens entre categorias.
- `MainActivity.kt` / `PhotoAdapter.kt`: chips com contagem/MB, grade reciclada
  nativa com loadThumbnail, seleção, progresso e uma createTrashRequest.
- `PhotoActivity.kt`: prévia ampliada local, GIF/WebP animados quando suportados.
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
- Única dependência nova admitida: ML Kit image-labeling:17.0.9, mais
  coroutines/lifecycle se necessários. Não incluir biblioteca de imagem ou UI.
- Arquivos UTF-8 sem BOM; editar com apply_patch. No PowerShell usar
  [IO.File]::WriteAllText se necessário, nunca Set-Content/Out-File.
- Responder em português, até 25 linhas; conclusão com arquivos/riscos até 20 linhas.

## Estado atual

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

- 24/10/2026: análise incremental com cache por foto, Configurações e ajustes visuais; sem mudanças de versão, dependências ou commits.
- 24/10/2026: app criado do zero, adaptando build, wrapper, assinatura, workflow,
  atualizador e FileProvider dos arquivos indicados no projeto de referência.
  Nenhum commit ou push realizado.
