package io.smallrye.graphql.index.inheritance;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;

@GraphQLApi
@Name("Businesses")
public class BusinessGraphQLApi extends AbstractGraphQLApi<Business, Long> {

    @Override
    Map<String, Business> getValues() {
        return VALUES;
    }

    private static Map<String, Business> VALUES = new HashMap<>();

    static {
        Business business1 = new Business(1L, "Business 1");
        VALUES.put(business1.getName(), business1);

        Business business2 = new Business(2L, "Business 2");
        VALUES.put(business2.getName(), business2);
    }
}
