
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
