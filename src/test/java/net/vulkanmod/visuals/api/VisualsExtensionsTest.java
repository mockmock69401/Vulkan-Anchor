package net.vulkanmod.visuals.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualsExtensionsTest {
    private final List<VisualsExtension> added = new ArrayList<>();

    private VisualsExtension extension() {
        VisualsExtension e = new VisualsExtension() {
        };
        added.add(e);
        return e;
    }

    @AfterEach
    void clear() {
        for (VisualsExtension e : added) {
            VisualsExtensions.unregister(e);
        }
        added.clear();
    }

    @Test
    void registerTwiceKeepsOne() {
        VisualsExtension e = extension();
        VisualsExtensions.register(e);
        VisualsExtensions.register(e);
        assertEquals(1, VisualsExtensions.all().stream().filter(x -> x == e).count());
    }

    @Test
    void orderIsRegistrationOrder() {
        VisualsExtension a = extension();
        VisualsExtension b = extension();
        VisualsExtension c = extension();
        VisualsExtensions.register(b);
        VisualsExtensions.register(a);
        VisualsExtensions.register(c);
        List<VisualsExtension> all = VisualsExtensions.all();
        int ia = all.indexOf(a), ib = all.indexOf(b), ic = all.indexOf(c);
        assertTrue(ib < ia && ia < ic);
    }

    @Test
    void allIsUnmodifiable() {
        VisualsExtension e = extension();
        VisualsExtensions.register(e);
        List<VisualsExtension> all = VisualsExtensions.all();
        assertThrows(UnsupportedOperationException.class, () -> all.add(extension()));
        assertThrows(UnsupportedOperationException.class, () -> all.remove(e));
    }

    @Test
    void unregisterRemoves() {
        VisualsExtension e = extension();
        VisualsExtensions.register(e);
        VisualsExtensions.unregister(e);
        assertEquals(-1, VisualsExtensions.all().indexOf(e));
    }
}
