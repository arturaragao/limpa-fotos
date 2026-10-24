# Limpa Fotos

App Android gratuito (Android 11+, Kotlin) para revisar prints, GIFs/figurinhas,
fotos borradas, repetidas e comida. Tudo é analisado no aparelho; ML Kit vem
com o modelo no APK. Fotos nunca são enviadas. Só o atualizador acessa a rede.

Permita acesso à galeria, toque para marcar/desmarcar e segure para ampliar.
Favoritos e a melhor foto de cada grupo começam desmarcados. A seleção é global
entre categorias; uma foto conta uma vez. O botão de lixeira abre **uma confirmação
do Android**, que mantém os itens na lixeira por 30 dias. Não há exclusão direta.

Widget 1×1 abre o app. Lembretes a cada 30 dias contam só metadados de prints e
figurinhas, sem ML e sem favoritos. No Android 13+, ative as notificações no app.
O Android pode adiar os trabalhos em segundo plano para economizar bateria.

## Build e atualização

O Actions usa Java 17, SDK 35, Gradle 8.13, roda JUnit e lint e gera a release
assinada com `KEYSTORE_B64`/`KEYSTORE_PASS`, PKCS12, alias `fotos`.
Cada execução publica `v0.1.N` com `limpa-fotos.apk` e versão `0.1.N`.
O wrapper JAR não é versionado: o CI o gera. Builds locais precisam de Java 17
e Android SDK: `gradle wrapper --gradle-version 8.13`, depois
`./gradlew testDebugUnitTest lintDebug assembleDebug`.

O atualizador consulta Releases ao abrir (no máximo uma vez por minuto) e usa
WorkManager com intervalo mínimo de 15 minutos; a instalação exige confirmação.

Limites: classificação visual é heurística; revise as sugestões. O limiar de
borrada é 100 (Laplaciano em cinza com lado máximo de 256 px). Repetidas têm
dHash até 6 bits e intervalo até uma hora, sem cadeias transitivas. A data de
adição substitui a de captura quando ausente. A maior resolução é preservada;
nitidez e tamanho desempatam. O cache em `filesDir` usa id + DATE_MODIFIED.

A grade usa provisoriamente `GridView` nativa: o modelo não inclui RecyclerView
e a restrição de novas dependências impede adicioná-la sem autorização.

Referências de API: [ML Kit embarcado](https://developers.google.com/ml-kit/vision/image-labeling/android),
[MediaStore](https://developer.android.com/reference/android/provider/MediaStore) e
[política de rede do Android](https://developer.android.com/privacy-and-security/security-config).
