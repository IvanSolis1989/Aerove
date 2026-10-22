package org.yaml.snakeyaml.representer;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.error.YAMLException;

public final class LiteSafeRepresenter extends SafeRepresenter {
    public LiteSafeRepresenter(DumperOptions options) {
        super(options);
        representers.put(null, value -> {
            throw new YAMLException("Unsupported configuration value type: " + value.getClass().getName());
        });
    }
}
