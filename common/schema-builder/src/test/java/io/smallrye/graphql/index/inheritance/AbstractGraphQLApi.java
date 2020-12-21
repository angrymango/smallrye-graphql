package io.smallrye.graphql.index.inheritance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.graphql.Query;

public abstract class AbstractGraphQLApi<E, ID> implements Queryable<E, ID> {
    @Query
    public List<E> getAll() {
        return new ArrayList<>(getValues().values());
    }

    abstract Map<String, E> getValues();
}
