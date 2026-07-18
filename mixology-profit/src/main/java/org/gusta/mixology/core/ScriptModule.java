package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;

public interface ScriptModule {
    String name();

    boolean shouldExecute(APIContext ctx);

    void execute(APIContext ctx);
}
