# doc_SCIVICS001/docs/Turing-workflow-plugins を書き直すときの指示

対象のディレクトリ: `~/works/doc_SCIVICS001/docs/Turing-workflow-plugins/`
ソース: `~/works/Turing-workflow-plugins/`（各プラグインの javadoc は、宣言が何をするかを使う人の側から書き直したばかり）

## 書く前に読むもの（必ず全部読む）

- `~/works/doc_Base010/docs/ProjectStandard2/010_AgentBrief_260905_oo01/010_AgentBrief_260905_oo01.md` — 共通の節構成、id、ファイル名、内部リンク
- `~/works/doc_Base010/docs/ProjectStandard2/030_ProgrammingProject/010_ProgrammingProject_260920_oo01/010_ProgrammingProject_260920_oo01.md` — ディレクトリ構造
- `~/works/doc_Base010/docs/ProjectStandard2/030_ProgrammingProject/200_documentation/` の下の全文書
  （`005_ConceptDocumentStandard`、`007_HowToExplainAProgram`、`010_TestDocumentStandard`、`025_SubparagraphTemplates`、
  `030_OneAxisPerSection`、`100_AntiPattern` の下の全文書）

## 守ること

1. 文書は1本1ディレクトリ: `NNN_<id>/NNN_<id>.md`。front matter の `id` はディレクトリ名から番号を除いたものと一致させる。
   既存の文書の `id` は変えない。`title` と、1〜3行の `description` を置く。
2. 節構成は `## Problem Definition`（その下に `### 前提条件と達成状態`）、`## How to do it`、`## Under the Hood`、`## 参考資料`。
   `010_concepts` の Vision Statement だけは `## Business Requirement` の5節。`060_tests` は TestDocumentStandard の7項目に従う。
3. 本文は日本語。クラス名・メソッド名・アクション名・ファイル名・コマンド・オプション・型パラメータ（`ActorRef<T>` など）は
   必ずバッククォートで囲む。
4. 事実はソースで確かめる。ソースに無いこと、確かめていないことは書かない。古い文書とソースが食い違ったらソースが正しい。
   アクション名、引数、返すものは、そのプラグインの `@Action` メソッドと javadoc から取る。
5. バージョン番号と日付を本文に書かない。
6. 作りではなく、使う人に何ができるかを書く（「wrapper」「@Action で公開する」を説明の主にしない）。経緯（ported、legacy）や
   内部の設計メモの名前を書かない。手順の羅列で用途を置き換えない。
7. 他の文書へは document ID で参照し、`## 参考資料` に `<ul><li><span data-doc-id="..." data-relation="...">— 説明</span></li></ul>`
   の形で書く。削除した文書（`TuringWorkflowIntroduction_260403_oo01`、`TuringWorkflowArchitecture_260403_oo01`、
   `PluginPaperDatabases_260913_oo01`）を参照しない。Turing-workflow 本体の説明は `doc_SCIVICS001/docs/Turing-workflow/` の文書を参照してよい
   （id を確かめてから）。
8. 既存の文書は、内容（コマンド例、YAML 例、表）をできるだけ残して節に入れ直す。ソースと合わない例は直す。
9. git commit、push、Docusaurus のビルドはしない。バックグラウンドでの実行や並列のエージェントは使わない。
