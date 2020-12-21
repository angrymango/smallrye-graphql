package io.smallrye.graphql.index.inheritance;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.graphql.Query;

public interface Queryable<E, ID> {
    @Query
    default List<E> getAllWithQuery(String query) {
        return new ArrayList<>();
    }

    List<E> getAll();
}
