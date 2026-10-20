
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
