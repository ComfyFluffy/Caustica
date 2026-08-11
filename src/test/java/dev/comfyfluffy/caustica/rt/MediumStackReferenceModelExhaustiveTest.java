package dev.comfyfluffy.caustica.rt;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable reference model for medium.slang's identity stack, exercised exhaustively. The model and
 * the shader follow one semantic definition — enter pushes onto a depth-3 stack and fails closed when
 * full, exit removes the nearest identity match from the top down (a deep match removes only that
 * layer), an unmatched exit is a no-op, and air is a bottom sentinel that is never pushed. A change to
 * either side must land in both.
 */
final class MediumStackReferenceModelExhaustiveTest {

    private static final int AIR = 0;
    private static final int WATER = 1;
    private static final int GLASS = 2;
    private static final int ICE = 3;
    private static final int[] IDENTITIES = {WATER, GLASS, ICE};

    private record Snapshot(int current, int parent1, int parent2) {}

    private record Operation(boolean enter, int identity) {}

    /** The reference model: medium.slang's MediumStack over bare identities. */
    private static final class Stack {
        private int current = AIR;
        private int parent1 = AIR;
        private int parent2 = AIR;

        boolean push(int entered) {
            if (entered == AIR || parent2 != AIR) {
                return false;
            }
            parent2 = parent1;
            parent1 = current;
            current = entered;
            return true;
        }

        boolean exitMatched(int exiting) {
            if (exiting != AIR && current == exiting) {
                current = parent1;
                parent1 = parent2;
                parent2 = AIR;
                return true;
            }
            if (exiting != AIR && parent1 == exiting) {
                parent1 = parent2;
                parent2 = AIR;
                return true;
            }
            if (exiting != AIR && parent2 == exiting) {
                parent2 = AIR;
                return true;
            }
            return false;
        }

        int depth() {
            return (current != AIR ? 1 : 0) + (parent1 != AIR ? 1 : 0) + (parent2 != AIR ? 1 : 0);
        }

        Snapshot snapshot() {
            return new Snapshot(current, parent1, parent2);
        }
    }

    @Test
    void exhaustiveSequencesPreserveEveryInvariant() {
        enumerate(new ArrayList<>(), 6);
    }

    private static void enumerate(List<Operation> prefix, int remaining) {
        verify(prefix);
        if (remaining == 0) {
            return;
        }
        for (int identity : IDENTITIES) {
            prefix.add(new Operation(true, identity));
            enumerate(prefix, remaining - 1);
            prefix.set(prefix.size() - 1, new Operation(false, identity));
            enumerate(prefix, remaining - 1);
            prefix.removeLast();
        }
    }

    private static void verify(List<Operation> sequence) {
        Stack stack = new Stack();
        List<Integer> expected = new ArrayList<>();
        for (Operation op : sequence) {
            Snapshot before = stack.snapshot();
            if (op.enter()) {
                boolean pushed = stack.push(op.identity());
                if (expected.size() == 3) {
                    assertFalse(pushed, "a full stack must fail the push closed");
                    assertEquals(before, stack.snapshot(), "a failed push must not disturb any layer");
                } else {
                    assertTrue(pushed);
                    expected.add(op.identity());
                }
            } else {
                boolean matched = stack.exitMatched(op.identity());
                int nearest = expected.lastIndexOf(op.identity());
                if (nearest < 0) {
                    assertFalse(matched, "an unmatched exit must report no match");
                    assertEquals(before, stack.snapshot(), "an unmatched exit must be a no-op");
                } else {
                    assertTrue(matched);
                    expected.remove(nearest);
                }
            }
            assertMirrors(expected, stack);
        }
    }

    private static void assertMirrors(List<Integer> expected, Stack stack) {
        assertTrue(stack.depth() >= 0 && stack.depth() <= 3, "depth stays within 0..3");
        assertEquals(expected.size(), stack.depth());
        assertEquals(expected.isEmpty() ? AIR : expected.getLast(), stack.current,
                "current is always the most recent unexited medium");
        assertEquals(expected.size() > 1 ? expected.get(expected.size() - 2) : AIR, stack.parent1);
        assertEquals(expected.size() > 2 ? expected.get(expected.size() - 3) : AIR, stack.parent2);
        assertFalse(stack.current == AIR && stack.parent1 != AIR, "air never sits above a real layer");
        assertFalse(stack.parent1 == AIR && stack.parent2 != AIR, "air never sits above a real layer");
    }

    @Test
    void strictlyNestedSequencesEqualPlainLifo() {
        for (int first : IDENTITIES) {
            for (int second : IDENTITIES) {
                for (int third : IDENTITIES) {
                    Stack stack = new Stack();
                    assertTrue(stack.push(first));
                    assertTrue(stack.push(second));
                    assertTrue(stack.push(third));
                    assertEquals(new Snapshot(third, second, first), stack.snapshot());
                    assertTrue(stack.exitMatched(third));
                    assertEquals(new Snapshot(second, first, AIR), stack.snapshot());
                    assertTrue(stack.exitMatched(second));
                    assertEquals(new Snapshot(first, AIR, AIR), stack.snapshot());
                    assertTrue(stack.exitMatched(first));
                    assertEquals(new Snapshot(AIR, AIR, AIR), stack.snapshot());
                }
            }
        }
    }

    @Test
    void nonNestedOverlapRecoversTheTrueSurroundingMedium() {
        Stack stack = new Stack();
        assertTrue(stack.push(ICE));
        assertTrue(stack.push(GLASS));
        assertTrue(stack.exitMatched(ICE));
        assertEquals(new Snapshot(GLASS, AIR, AIR), stack.snapshot(),
                "exiting the deeper ice keeps glass current with direction untouched");
        assertTrue(stack.exitMatched(GLASS));
        assertEquals(new Snapshot(AIR, AIR, AIR), stack.snapshot());
    }

    @Test
    void noMatchExitIsIdentityAndOverflowFailsClosed() {
        Stack stack = new Stack();
        assertTrue(stack.push(WATER));
        assertTrue(stack.push(GLASS));
        assertTrue(stack.push(GLASS));
        Snapshot full = stack.snapshot();
        assertFalse(stack.exitMatched(ICE));
        assertEquals(full, stack.snapshot());
        assertFalse(stack.push(ICE));
        assertEquals(full, stack.snapshot());
        assertFalse(stack.push(AIR));
        assertEquals(full, stack.snapshot());

        assertTrue(stack.exitMatched(GLASS));
        assertEquals(new Snapshot(GLASS, WATER, AIR), stack.snapshot(),
                "one exit removes one nesting level of a repeated identity");
    }
}
