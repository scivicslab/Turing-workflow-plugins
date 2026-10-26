package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.turingworkflow.workflow.MatrixCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example workflow shipped in the jar calls only actions that exist, in the argument form they declare.
 * Nothing is run and no LLM is called.
 */
class KanaKanjiPairsExampleTest {

    private static final String RESOURCE = "/examples/kana-kanji-pairs.yaml";

    @Test
    void everyActionExistsWithItsArgumentForm() throws Exception {
        MatrixCode code = ExampleWorkflowCheck.parse(RESOURCE);

        assertThat(code.getTransitions()).isNotEmpty();
        assertThat(ExampleWorkflowCheck.problems(code)).isEmpty();
    }

    @Test
    void loadsThisModulesOwnVersion() throws Exception {
        Object coordinate = ExampleWorkflowCheck.parse(RESOURCE).getTransitions().get(0).getActions().get(0).getArguments();

        assertThat(String.valueOf(coordinate))
                .matches("com\\.scivicslab\\.turingworkflow\\.plugins:plugin-kana-kanji:\\d+\\.\\d+\\.\\d+.*");
    }
}
