package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a book's OCR'd markdown into headings, paragraphs of sentences, code and lists
 * ({@code FramesAndSamplesFromBooks_261021_oo01}). The inputs are cut from the two books the
 * workflow is first run on.
 */
@DisplayName("本の OCR の markdown を、見出し・文の並びの段落・コード・リストに読む")
class BookTextTest {

    /** Core Java, read with Marker: headings and fences are there; paragraphs break mid-sentence. */
    private static final String MARKER = """
            ---
            title: "3. Fundamental Programming Structures in Java"
            pages: "1-5"
            ocr_backend: "marker"
            ---

            ![](p1__page_0_Picture_2.jpeg)

            ## **3.1. A Simple Java Program**

            Let's look more closely at one of the simplest Java programs you can have—one that merely prints a message to console:

            ```
            void main() {
             IO.println("We will not use 'Hello, World!'");
            }
            ```

            First and foremost, *Java is case sensitive*. If you made any mistakes in capitalization (such as typing Main instead of main ), the program

            will not run.

            The term "method" is Java-speak for a function. You *must* have a main method in every program.

            Note: see **[Chapter 4](https://learning.oreilly.com/x/ch4/index.xhtml)** for what the keywords mean.

            To run any of the programs in this chapter with an older version of Java, do the following:

            - 1. Place all code inside a class whose name equals the file name, without the extension.
            - 2. Declare main in the old style.
            """;

    /** アルゴリズムとデータ構造, read with YomiToku: page numbers, running heads, folded lines. */
    private static final String YOMITOKU_1 = """
            ---
            title: "アルゴリズムとデータ構造"
            pages: "101-110"
            ocr_backend: "yomitoku-marker"
            ---

            2.4 2分探索木
            77

            この手続きを insert(x, y, root)の形で呼び出せばよい. 手続き insert は,
            search と同じ方法で木を上から下に進みながら,データを挿入すべき場所を
            探す.

            78
            2 探 索

            木の形によって計算量が大きく変わるのが要点である。探索の計算量は、根から目標の頂点に至るまでに
            """;
    private static final String YOMITOKU_2 = """
            ---
            title: "アルゴリズムとデータ構造"
            pages: "111-120"
            ---

            調べる頂点の数にほぼ比例する.

            80
            2 探 索

            2.4 2分探索木
            79
            """;

    @Test
    @DisplayName("Marker の本: 見出し・コード・リストを分け、文の途中で割れた段落をつなぎ、記法と画像を除く")
    void aMarkerBook() {
        List<BookText.Block> blocks = BookText.read(List.of(MARKER));

        assertEquals(BookText.Kind.HEADING, blocks.get(0).kind());
        assertEquals("3.1. A Simple Java Program", blocks.get(0).text());
        assertEquals(BookText.Kind.PARAGRAPH, blocks.get(1).kind());
        assertEquals(BookText.Kind.CODE, blocks.get(2).kind());
        assertTrue(blocks.get(2).text().contains("IO.println(\"We will not use 'Hello, World!'\");"));

        BookText.Block caseSensitive = blocks.get(3);
        assertEquals(List.of("First and foremost, Java is case sensitive.",
                "If you made any mistakes in capitalization (such as typing Main instead of main), the program will not run."),
                caseSensitive.sentences());

        assertEquals(List.of("The term \"method\" is Java-speak for a function.",
                "You must have a main method in every program."), blocks.get(4).sentences());
        assertEquals(List.of("Note: see Chapter 4 for what the keywords mean."), blocks.get(5).sentences());

        BookText.Block list = blocks.get(7);
        assertEquals(BookText.Kind.LIST, list.kind());
        assertEquals(List.of("Place all code inside a class whose name equals the file name, without the extension.",
                "Declare main in the old style."), list.sentences());
        for (BookText.Block b : blocks) {
            assertFalse(b.text().contains("Picture"), "the image line is dropped: " + b);
        }
    }

    @Test
    @DisplayName("YomiToku の本: ページ番号と繰り返す柱を除き、折り返した行とファイルの境目で割れた段落をつなぐ")
    void aYomitokuBookAcrossTwoFiles() {
        List<BookText.Block> blocks = BookText.read(List.of(YOMITOKU_1, YOMITOKU_2));

        assertEquals(2, blocks.size(), blocks.toString());
        assertEquals(List.of("この手続きを insert(x, y, root)の形で呼び出せばよい.",
                "手続き insert は, search と同じ方法で木を上から下に進みながら,データを挿入すべき場所を探す."),
                blocks.get(0).sentences());
        assertEquals(List.of("木の形によって計算量が大きく変わるのが要点である。",
                "探索の計算量は、根から目標の頂点に至るまでに調べる頂点の数にほぼ比例する."),
                blocks.get(1).sentences());
    }

    @Test
    @DisplayName("引用符の内側で終わる文の後で段落を切り、囲みの無いコードの 1 行はコードにする")
    void aQuotedEndAndAnUnfencedLineOfCode() {
        List<BookText.Block> blocks = BookText.read(List.of("""
                This is sometimes called "camel case" or, self-referentially, "CamelCase."

                Note: Prior to Java 25, you had to declare the class.

                Now turn your attention to the contents inside the braces of the main method,

                IO.println("We will not use 'Hello, World!'");

                This is the body of the method.
                """));
        assertEquals(List.of("This is sometimes called \"camel case\" or, self-referentially, \"CamelCase.\""),
                blocks.get(0).sentences());
        assertEquals(List.of("Note: Prior to Java 25, you had to declare the class."), blocks.get(1).sentences());
        assertEquals(BookText.Kind.CODE, blocks.get(3).kind(), blocks.toString());
        assertEquals("IO.println(\"We will not use 'Hello, World!'\");", blocks.get(3).text());
        assertEquals(List.of("This is the body of the method."), blocks.get(4).sentences());
    }

    @Test
    @DisplayName("空白の無い「.」で日本語の文を切り、階乗の ! と小数の点では切らず、小見出しと図の見出しを分ける")
    void japaneseSentencesHeadingsAndCaptions() {
        List<BookText.Block> blocks = BookText.read(List.of("""
                根から木の下端までの距離が等しい木である.このような木を完全 2 分木と称する.1.2節で述べたように,n 個のデータを挿入する n! とおりの順序を考える.

                (c) 計算量

                2分探索木を使った探索の計算量を調べよう.

                図2.4.6 2分探索木の最悪の形

                最善の場合は O(log n) である.
                """));
        assertEquals(List.of("根から木の下端までの距離が等しい木である.", "このような木を完全 2 分木と称する.",
                "1.2節で述べたように,n 個のデータを挿入する n! とおりの順序を考える."), blocks.get(0).sentences());
        assertEquals(BookText.Kind.HEADING, blocks.get(1).kind(), blocks.toString());
        assertEquals("(c) 計算量", blocks.get(1).text());
        assertEquals(List.of("2分探索木を使った探索の計算量を調べよう."), blocks.get(2).sentences());
        assertEquals(List.of("最善の場合は O(log n) である."), blocks.get(3).sentences(), "the caption is dropped: " + blocks);
    }

    @Test
    @DisplayName("1 行ずつ割れたコードは 1 つのコードに、ラベルだけの行の並びは 1 つのリストにつなぐ")
    void consecutiveCodeLinesAndLabelledLines() {
        List<BookText.Block> blocks = BookText.read(List.of("""
                次の手続きで探す.

                lo := 1; hi := n;

                found := (lo = hi+2);

                図の五つの木は,次の順序で挿入した結果である.

                (a) 1-2-3

                (b) 1-3-2

                (c) 計算量
                """));
        assertEquals(BookText.Kind.CODE, blocks.get(1).kind(), blocks.toString());
        assertEquals("lo := 1; hi := n;\nfound := (lo = hi+2);", blocks.get(1).text());
        assertEquals(BookText.Kind.LIST, blocks.get(3).kind(), blocks.toString());
        assertEquals(List.of("(a) 1-2-3", "(b) 1-3-2"), blocks.get(3).sentences());
        assertEquals(BookText.Kind.HEADING, blocks.get(4).kind());
    }
}
