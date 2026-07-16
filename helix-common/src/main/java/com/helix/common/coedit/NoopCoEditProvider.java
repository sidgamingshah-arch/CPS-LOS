package com.helix.common.coedit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEFAULT co-edit provider ({@code helix.coedit.provider=none}, also the absent-property
 * default via {@code matchIfMissing}). Makes NO external call: it returns the local
 * artifact (link + bytes) exactly as the existing export flow already does. This is
 * today's behaviour verbatim, so enabling co-edit as a feature does not change any
 * running deployment until an operator opts in with {@code provider=graph}.
 */
@Component
@ConditionalOnProperty(name = "helix.coedit.provider", havingValue = "none", matchIfMissing = true)
public class NoopCoEditProvider implements CoEditProvider {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public CoEditResult publish(CoEditRequest request) {
        return CoEditResult.local(name(), request);
    }
}
