package com.dsh.maidmanager;

import com.dsh.maidmanager.client.ClientSelection;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the rule that the summon/recall hotkey only ever touches maids the player ticked.
 *
 * <p>This is the safety property that stops a stray key press from dragging every maid in
 * the world to the player, so it is worth a regression test even though the full hotkey
 * path needs a client.
 */
public class ClientSelectionTest {

    @After
    public void tearDown() {
        ClientSelection.clear();
    }

    @Test
    public void emptySelectionMeansNothingIsTargeted() {
        assertTrue("a fresh selection is empty", ClientSelection.get().isEmpty());
    }

    @Test
    public void togglingAddsThenRemoves() {
        UUID id = UUID.randomUUID();
        ClientSelection.toggle(id);
        assertTrue(ClientSelection.isSelected(id));

        ClientSelection.toggle(id);
        assertFalse("toggling twice clears the tick", ClientSelection.isSelected(id));
    }

    @Test
    public void setControlsMembershipExplicitly() {
        UUID id = UUID.randomUUID();
        ClientSelection.set(id, true);
        assertTrue(ClientSelection.isSelected(id));

        ClientSelection.set(id, false);
        assertFalse(ClientSelection.isSelected(id));
    }

    @Test
    public void retainDropsMaidsThatNoLongerExist() {
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        ClientSelection.set(kept, true);
        ClientSelection.set(gone, true);

        // Simulate a refresh where one maid is no longer in the snapshot.
        ClientSelection.retain(List.of(kept));

        assertTrue("still-present maid stays ticked", ClientSelection.isSelected(kept));
        assertFalse("vanished maid is unticked", ClientSelection.isSelected(gone));
    }

    @Test
    public void getIsReadOnlySoCallersCannotMutateTheSelectionByAccident() {
        try {
            ClientSelection.get().add(UUID.randomUUID());
            org.junit.Assert.fail("get() must return an unmodifiable view");
        } catch (UnsupportedOperationException expected) {
            // correct behaviour
        }
    }

    @Test
    public void clearRemovesEverything() {
        ClientSelection.set(UUID.randomUUID(), true);
        ClientSelection.set(UUID.randomUUID(), true);
        assertEquals(2, ClientSelection.get().size());

        ClientSelection.clear();
        assertTrue(ClientSelection.get().isEmpty());
    }
}
