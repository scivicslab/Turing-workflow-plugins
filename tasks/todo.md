
# plugin-log-db: 書き込みの完了を、手書きのスレッドとラッチではなく POJO-actor の `ask` で受け取る（未承認の段階あり）

背景: `H2LogStore` は、書き込み用の `Thread`・`LinkedBlockingQueue`・`CountDownLatch`・`AtomicReference` を自前で持つ。
アクターの `tell`/`ask` が与えるメールボックスと返答を、作り直している。`startSession`/`endSession` の返答（ラッチ）が
バッチのコミットより前に解ける不具合（4.4.0 で修正）は、この作り直しの結果である。
`chat-ui-with-audit-trail` の `ArtexProxyLoggingTest` も、完了の合図が無いため `sleep` のループで待っている。
アンチパターン文書（`doc_SCIVICS001/docs/POJO-actor/400_AntiPatterns/`）の `ActorSuffixAndOwnedActorRef`（ライブラリが持つ仕組みの作り直し）、
`LookupThatCreates`（引く操作が実体を作る：`ensureSession`/`ensureJobSession`）、
`ExitConditionRidingOnAFailure`（終わりを時間切れで判定する）に当たる。

影響範囲（調査済み）: `new H2LogStore(path, compress)` を使う製品は 3 つ。`chat-ui-with-audit-trail`、`quarkus-chat-ui`、`quarkus-chat-ui3`。
それぞれの `IoLogStore` が `DistributedLogStore` を `synchronized` で包んで使う。`H2LogStore` の書き込みを同期にすると、
3 製品のリクエストスレッドがディスクの fsync を待つようになる。
`chat-ui-with-audit-trail` の pom は、`plugin-log-db` から pojo-actor と turing-workflow を除外している
（「H2 のクラスはこれらを参照しない」）。`H2LogStore` に ActorRef を持ち込むと、この独立が崩れる。

段階:
- [ ] 段階 1（追加のみ・互換）: `DistributedLogStore` に、これまでに積んだ書き込みのコミット完了まで待つ `flushWrites()` を公開する
      （既存の `FlushSentinel` を使う。新しい機構は足さない）。`ArtexProxyLoggingTest` などは、`sleep` のループを外し、
      書いた直後に `flushWrites()` を呼んでから読む。`tearDown` でアクターシステムを閉じる。30 秒の待ちを戻す。→ 4.4.1
- [ ] 段階 2（POJO 化）: `H2LogStore` を、自前のスレッドとキューを持たない素の同期 POJO にする。書き込みの直列化は、
      呼び出す側が `ActorRef<DistributedLogStore>` で包んで行う（`LogStoreActor` が既にこの形）。
      `chat-ui-with-audit-trail` の `IoLogStore` から `synchronized` と `HashMap` を外し、`ask(...).join()` で呼ぶ。
      `quarkus-chat-ui`・`quarkus-chat-ui3` は別の変更として、それぞれの判断で追随する。
- [ ] 段階 3: `ensureSession`/`ensureJobSession` のように、引くつもりで呼ぶと作るメソッドを、引くものと作るものに分ける。
      `ChatUiActorSystem.hasChat` は、アプリ側の `chats` ではなく `ActorSystem` の登録簿に問い合わせる。

## Lesson (2026-10-24): the gathered frame material is a result in itself

Each concept's rst-on-lisp rules, gathered from the whole section (properties and behaviors), is a key result: material
scattered over the book now sits in one place. The next step builds on it: name the questions a concept's explanation
answers (the items) and assign each rule to one.

## 2026-10-25: rst-frame の大きい節を作る原因を直す
背景: texbook 第27節（539,060 文字、付録 A〜I が第27章に連結）、common-lisp-object-system 3.4.3（351万文字）、
cissp 1.4、ゲームAI技術入門 1.1.19、jdk25-javadoc 2.9.99（番号の各段が2桁まで）など。走っている3冊と DB・run2/run3 は触らない。
- [x] a. `BookSections.splitLarge`: 上限（max(50,000, 中央値×4) 文字）を超える節を、中の見出しで分ける（見出しが無ければ段落で）。分けた片は `N.pK` の番号の子節。`BookImport sections` が toc_node と section_text を揃えて保存し、再実行時は前回の片を除いてから分け直す
- [x] b. `BookOutline.NUMBERED_ANY`: 2段目以降を `\d{1,3}` まで読む（1段目は2桁のまま）
- [x] c. `OutlineByModel.number`: 名前を持つ章に来た「name」は章名に足さず、その章の節にする
- [x] d. `BookOutline.withDirectoryChapters`: 「NN_第N章 …」「NN_付録X …」のディレクトリを、モデルや規則の目次に無い章・付録として足す（toc と toc-llm の両方）
- [x] 単体テスト（@TempDir のみ）、`rm -rf target && mvn install`、`db/logs/plugin-rst-frame-run4.jar` に置く
- [x] texbook を一時 DB で toc → sections し、第27節が付録ごとに分かれるか確かめる
- [x] やり直しの手順書（`rst-on-lisp/db/logs/redo-plan.md`）
- 結果: 単体テスト 156 本緑（新規 5 本）。`db/logs/plugin-rst-frame-run4.jar`。一時 DB で texbook（toc-llm）: 付録 A〜I が章になり、
  第27節 539,060 → 約 13,000＋27,000 文字、最大の節 95,153 文字（上限 100,000、片なし）。jdk25-javadoc（toc）: 6,706 → 7,136 節、
  最大 50,021。common-lisp-object-system（toc）: 3.4.3 の 3,512,357 文字が 59 片に、106 → 167 節
- 本物の DB と走っている処理には触れていない。やり直しは `rst-on-lisp/db/logs/redo-plan.md` に書いただけで、未実行

## 2026-10-26: 全プラグインの javadoc を、JDK25 javadoc の型で書き直す
型（クラス: 何であるか → 主な操作と返すもの → 保つ状態 → 共有してよいか → 例 → 限界 → 近いクラスとの違い。
メソッド: 何をするか → 保証と条件 → @param/@return/@throws。フィールド: 値の意味）を、JDK25 javadoc のフレーム
（ArrayList、ExecutorService、Thread、Formatter の単位と性質）から取った。
- [x] 道具 `rst-on-lisp/tools/JavadocRewrite.java`: コメント1つごとに、型とドメインの説明（`tools/javadoc-domain.md`、
      プラグインの仕様文書）とソース全体を local LLM に渡して書き直させ、別の問いで型と禁止事項（作りの説明・経緯・
      設計メモの名前・手順の列挙・日本語・あいまいな動詞）を確かめる。3回まで。コードで確かめられる誤り（`*/`、日本語、
      @param の名前）があるものは元のまま残す
- [x] plugin-web で道具を確かめた（12個すべて書き直し）
- [x] 全20プラグイン（`db/logs/javadoc/run-all.sh`、ログは `db/logs/javadoc/<plugin>.tsv`）。メソッドの型は 007 の節に合わせて差し替えてから流し直した
- [x] `rm -rf target && mvn install`: 全20プラグインでテストと javadoc の生成が通った
- 結果: 1,656 個のうち 1,646 個を書き直した（1回目で通過 998、2〜3回目で通過 357、3回差し戻され最後の版を使った forced 291、元のまま 10）。
  閉じていない `<li` が残った4プラグインは検査を足して流し直した。抜き取りで、段落の `<p>` 抜け、ブロックタグの後ろに本文が続くもの、
  静的なユーティリティにも「several workflows at once」を書くものが見つかった（未修正）

## 2026-10-26: doc_SCIVICS001/docs/Turing-workflow-plugins を ProgrammingProject 標準に合わせる
標準: `ProgrammingProject_260920_oo01`、`AgentBrief_260905_oo01`、`ConceptDocumentStandard_260922_oo01`、
`TestDocumentStandard_260922_oo01`、`HowToExplainAProgram_261004_oo01`、`AntiPattern_260921_oo01`。決定: 本体の説明2本と 005_index は削除、
Vision Statement の分からない所は空欄、新しいプラグインは1プラグイン1文書、本文は全部 Claude Code が書く。
- [ ] ディレクトリを組み替える（git mv）: 050_tutorials→020_tutorials、020_specs の使い方13本→030_operations、
      PluginDevelopment→050_implementation、025_tests→060_tests（1文書1ディレクトリ）。010_concepts の2本と 005_index.md を削除
- [ ] 010_concepts: Vision Statement（Business Requirement の5節、分からない所は空欄）とコンセプト文書（コアのアイディア、システム構成、20プラグインの一覧と依存関係）
- [ ] 030_operations: 論文の3プラグインを1プラグイン1文書に分け、文書の無い8プラグイン（chatui3、codedoc、fineweb-search、kana-kanji、ocr、rst-frame、text、web）の文書を足す
- [ ] 全文書を共通の節構成（Problem Definition／前提条件と達成状態／How to do it／Under the Hood／参考資料）に直す。060_tests は7項目に合わせる。
      事実はソースで確かめ、バージョン・日付は書かない
- [ ] 確認: 全文書の id とファイル名・ディレクトリ名の一致、節の有無、description、data-doc-id の参照先が存在すること

## 2026-10-26: doc_SCIVICS001/docs/Turing-workflow-plugins/030_operations の14本を共通の節構成で書き直す
指示は `tasks/doc-refactor-brief.md`。1本ずつ、ソースと突き合わせて直す。190_PluginPaperDatabases は触らない。
- [x] 010_Installation
- [x] 020_SshSetup
- [x] 030_CliCommands
- [x] 040_DatabaseConnection
- [x] 100_PluginSsh
- [x] 110_PluginInventory
- [x] 120_PluginLogDb
- [x] 130_PluginLogOutput
- [x] 140_PluginReport
- [x] 150_PluginLlm
- [x] 155_PluginLlm_Direct
- [x] 160_PluginPromptBuilder
- [x] 170_PluginSecret
- [x] 180_PluginVault
- 確かめ方: `run` で動く例（loader で読み込むプラグイン）は `/tmp/twdoc` で実行し、LLM と Vault は手元の模擬サーバで確かめた。
  `nodeGroup` / `logStore` / `outputMultiplexer` の例は、それらを登録する Java のホストプログラム（`/tmp/twdoc/host/HostDemo.java`）で確かめた。
- 見つけたソースの不具合（コードは直していない。2つ目と3つ目は文書に制限として書いた。1つ目は文書に書いていない）:
  - `NodeGroupActor.createNodeActors` が同じ名前を2回登録し、POJO-actor の名前の重複検査（0711270）で `IllegalStateException`。ノードのアクターが作れない
  - `NodeGroupActor.apply` / `executeCommandOnAllNodes` / `Interpreter.apply` が引数を JSON 配列で渡すので、argsType を持つアクション（`executeCommand`、`runWorkflow` など）は引数の検査で必ず失敗する
  - `LlmClient.unwrapJsonArray` が `[` で始まるプロンプトを配列として読み、`PromptBuilderActor.build` の結果（`[Constraints]...`）が `Constraints` の1語に切り詰められる
  - コンセプト文書と README の `setOpenAiUrl` の例の `url` が `/v1/chat/completions` を含まない（`callOpenAi` はその URL にそのまま POST する）

## 2026-10-26: 030_operations に 11 プラグインの文書を書く（論文3本の分割と、文書の無い8プラグイン）
指示は `tasks/doc-refactor-brief.md`。1プラグイン1文書、YAML の例は `run` で実行して確かめる。
- [x] 190_PluginArxiv / 200_PluginOpenAlex / 210_PluginSemanticScholar（実サービスに数問）。190_PluginPaperDatabases は `git rm -r`
- [x] 220_PluginWeb（DuckDuckGo に1問、ページは手元の http.server）、230_PluginFineWebSearch（模擬サーバ）、240_PluginChatUi3（模擬サーバ）
- [x] 250_PluginKanaKanji（GPU broker に2問）、260_PluginOcr（模擬サーバ）、270_PluginCodeDoc、280_PluginText、290_PluginRstFrame（GPU broker に7問程度）
- [x] コンセプト文書の参考資料に 11 本の id を足す
- 見つけたソースの不具合（コードは直していない。文書の Under the Hood に書いた）:
  - `CodeDocActor` がリストのアクターの `add` と `get` に文字列をそのまま渡すので、argsType の検査で失敗する。
    `chunkFile`・`scanProject`・`listMarkdownNoDescription` は数を返すがリストは空、`writeFileFromList`・`joinListToStr` は検査の失敗の文を書く
  - `ChatUi3Actor` を作ったワークフローは、`run` の終了時に `ChatUi3WatchdogActor.close` が閉じ済みの `ChatUi3Actor` を触って
    `NullPointerException` になり、プロセスが終了しない
  - `plugin-rst-frame` は H2 を `loadJar`（や `plugins.yaml`）で読み込むと `No suitable driver found`。JVM のクラスパスに置く必要がある
  - `SemanticScholarClient.resolveId` の `https://doi.org/` の分岐は `:` の判定が先にあるので通らない
  - `plugin-kana-kanji` の jar の `examples/kana-kanji-pairs.yaml` は引数を文字列で渡しており、今のアクションでは動かない

## 2026-10-26: 020_tutorials の14本を共通の節構成で書き直す
指示は `tasks/doc-refactor-brief.md`。1本ずつ、id とパスはそのまま。各 YAML は `run`（turing-workflow の shaded jar、プラグインは ~/.m2）で実行して確かめる。
外部サービスは手元の模擬サーバ。SSH は sshd に設定変更なしで localhost に入れる場合だけ実行する。todo.md の不具合で動かない例は、手順を残して Under the Hood に書く。
- [x] 010_FirstWorkflow（実行: createNodeActors の不具合で失敗。NodeActor を1回だけ登録する検証用プログラムで後段を確認）
- [x] 020_ParallelExecution（実行: createNodeActors で失敗。検証用プログラムで apply の引数の失敗と、集計の後段を確認）
- [x] 030_SystemInventory（実行: createNodeActors で失敗。サブワークフローは検証用プログラムで手元の計算機に対して確認。SSH は未実行）
- [x] 040_UtilizingResults（実行: 失敗する実行のログを log-search・db-clear で読めることを確認。-n の行は検証用プログラムの実行から）
- [x] 050_ReportGeneration（実行: 基本の報告は成功。用途別2本は createNodeActors で失敗、検証用プログラムで後段を確認。GPU の節は固定の3行で確認）
- [x] 060_OverlaySystem（実行: run -o で3通りとも成功。describe -o が namePrefix のあるオーバーレイを当てずにベースを表示することを見つけた）
- [x] 070_CowsayCharacters（実行: 既定・tux・elephant・meow・知らない名前を確認。一覧は listCowfiles の44種類）
- [x] 080_PluginSsh（実行: createNodeActors で失敗。検証用プログラムで SUDO_PASSWORD なしの経路まで確認。sudo 付きと遠隔の SSH は未実行）
- [x] 090_PluginInventory（実行: InventoryCheck は成功。メインは createNodeActors で失敗。--list-nodes の形は検証用プログラムの FAILED の出力から）
- [x] 100_PluginLlm（実行: 模擬サーバに対して run で成功。接続失敗と [ で始まるプロンプトの切り詰めも確認）
- [x] 110_PluginPromptBuilder（実行: 模擬サーバに対して run で YES・NO の両方の分岐を確認。ループは LLM なしで成功）
- [x] 120_PluginReport（実行: run の例は成功。ClusterRunner の例は createNodeActors で失敗、検証用プログラムで後段を確認）
- [x] 130_PluginSecret（実行: 鍵の生成・暗号化・復号・誤った鍵、RunWithSecrets の起動を確認。ワークフローは createNodeActors で失敗、SUDO_PASSWORD が渡ることは検証用プログラムで確認。sudo は未実行）
- [x] 140_PluginVault（実行: 模擬 Vault に対して run で成功と4通りの失敗を確認。kubectl exec は未実行。E2E の例は createNodeActors で失敗、検証用プログラムで後段を確認）
- 確かめ方: 作業は /tmp/twtut。NodeActor を使う例は `ClusterRunner.java`（010 に載せたもの）で実行し、不具合の後段は
  NodeActor を名前1つにつき1回だけ登録する検証用の `/tmp/twtut/verify/VerifyRunner.java` で確かめた。LLM と Vault は手元の模擬サーバ
  （`/tmp/twtut/mock/`）。GPU broker と実際の LLM・Vault には問い合わせていない。localhost への SSH は `~/.ssh/known_hosts` の
  ホスト鍵の食い違いで拒まれるので、SSH の例は実行していない（設定は変えていない）。
- 新しく見つけたこと（コードは直していない。060 の Under the Hood に書いた）:
  - `DescribeCLI.loadYamlWithOverlay` はファイル名の完全一致でしか探さないので、`namePrefix`/`nameSuffix` のあるオーバーレイでは
    何も告げずにベースのファイルを表示する（`run -o` は部分一致で探すので当たる）
  - `NodeGroupActor.printSessionSummary` は文書サイト構築用の決まった12項目の表を出し、一般のワークフローには使えない（090 から外した）

## 2026-10-26: 060_tests の4本と 050_implementation/010_PluginDevelopment を共通の節構成で書き直す
指示は `tasks/doc-refactor-brief.md`。060_tests は TestDocumentStandard の7項目に合わせる。コードとテストは変えない。
- [x] 010_TestsOverview（`rm -rf target */target && mvn test -DexcludedGroups=e2e`: 306 件成功、失敗・飛ばし 0）
- [x] 020_E2eSsh（E2E は未実行。`mvn test -pl plugin-ssh -DexcludedGroups=e2e` は 0 件で BUILD SUCCESS）
- [x] 030_E2eLlm（E2E テストはソースツリーに無いと明記。`mvn test -pl plugin-llm`: LlmClientTest 3 件成功）
- [x] 040_E2eVault（Vault に触れるテストは未実行。環境変数を外した `mvn test`: 3 件とも飛ばし、BUILD SUCCESS）
- [x] 010_PluginDevelopment（`/tmp/twplugin/plugin-wordcount` を作り、mvn install でテスト 3 件成功、run で loadJar→createChild→count/total と引数検査の失敗を確認。~/.m2 に入れた例の jar は消した）
- 見つけたこと（コードは直していない。文書の Under the Hood に書いた）:
  - 親 pom に excludedGroups が無く、`mvn test`/`mvn install` が `NodeE2eTest` を実行して 192.168.5.13 へ接続する。`VAULT_ADDR`/`VAULT_TOKEN` がシェルにあれば Vault の E2E も実行され、Vault に書く
  - `*E2eRunner` の javadoc の `mvn exec:java` のコマンドは `-Dexec.classpathScope=test` が無く、クラスが見つからずに失敗する
  - `VaultActorE2eTest.get_missingPath_returnsFailure` はマウントの設定にかかわらず `secret/` を読み、失敗の種類を区別しない
  - `PomXmlPlugin_260921_oo01` の表はエンジンへの依存を `compile` とするが、親 pom は `provided`

## 2026-10-26: 文書の書き直しで見つけた不具合を直す
1つずつ、落ちるテストを先に書いて（赤）、直して（緑）、`rm -rf target && mvn install`（-DskipTests なし）。commit はローカルだけ。
- [x] エンジン（Turing-workflow）: `Interpreter.apply` が子へ引数を JSON 配列で渡す／`DescribeCLI.loadYamlWithOverlay` が namePrefix・nameSuffix のあるオーバーレイを当てない
- [x] plugin-inventory: `createNodeActors` が同じ名前を2回登録する／`apply`・`executeCommandOnAllNodes` が名前つきの引数を渡せない
- [x] 親 pom: `e2e` のタグを既定で除く（E2E は明示したときだけ）／`*E2eRunner` の javadoc のコマンドに `-Dexec.classpathScope=test`
- [x] plugin-chatui3: 終了時の `ChatUi3WatchdogActor.close` の NPE で `run` が終わらない
- [x] plugin-codedoc: リストのアクターに文字列をそのまま渡す
- [x] plugin-rst-frame: `loadJar` で読み込んだ H2 を使えない（`No suitable driver found`）
- [x] plugin-llm: `[` で始まるプロンプトを配列として読む／javadoc の「MCP」／README の `setOpenAiUrl` の例
- [x] plugin-semantic-scholar: `resolveId` の DOI の URL の分岐が通らない
- [x] 例の YAML: plugin-kana-kanji の `kana-kanji-pairs.yaml`、plugin-llm の `llm-hello.yaml`
- [x] 文書: Under the Hood の不具合の記述を外し、動くようになったチュートリアルを実行し直す
- 結果: エンジン 950b305・02e741b・1aae8e2、プラグイン bde750b・9ceaf6e・db6a2cb・f154dd6・56488c4・ca55c2e・980a1cb・f2534e1・30b2891・9b2b52f・36bacd2、
  文書 doc_SCIVICS001 882883c。プラグイン全体の mvn install（e2e の除外を既定にした後、-DexcludedGroups なし）で 339 件成功、E2E は実行されない
- 残り（未修正）: rst-frame は `loadJar` で H2 を一緒に読み込む必要がある（無いと `NoClassDefFoundError` で `run` が終わらない）、
  `VllmClient`（kana-kanji）が応答の `\uXXXX` を解かない、`run -l WARNING` に `out` のアクターの警告が出る
