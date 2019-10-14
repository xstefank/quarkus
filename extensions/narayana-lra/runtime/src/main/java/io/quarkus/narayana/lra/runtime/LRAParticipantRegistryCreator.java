package io.quarkus.narayana.lra.runtime;

import java.util.HashMap;
import java.util.Map;

import javax.enterprise.context.spi.CreationalContext;

import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipant;
import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipantRegistry;
import io.quarkus.arc.BeanCreator;

public class LRAParticipantRegistryCreator implements BeanCreator<LRAParticipantRegistry> {

    public static Map<String, LRAParticipant> participants = new HashMap<>();

    @Override
    public LRAParticipantRegistry create(CreationalContext<LRAParticipantRegistry> creationalContext,
            Map<String, Object> params) {
        return new LRAParticipantRegistry(participants);
    }
}
