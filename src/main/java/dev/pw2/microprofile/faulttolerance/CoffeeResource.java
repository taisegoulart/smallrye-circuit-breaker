package dev.pw2.microprofile.faulttolerance;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import java.util.Collections;
import org.eclipse.microprofile.faulttolerance.Fallback;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

import org.jboss.logging.Logger;

@Path("/coffee")
public class CoffeeResource {
    private static final Logger LOGGER = Logger.getLogger(CoffeeResource.class);

    @Inject
    CoffeeRepositoryService coffeeRepository;

    private AtomicLong counter = new AtomicLong(0);

    @GET //TODO na documentação do circuit breaker diz que tem quye ver as falhas no output do dev server, mas não achei onde ficariam esses logs aqui pelo VS code
    @Retry(maxRetries = 4) //depois dessa anotação, o método ainda estará falhando em 50% do tempo porém toda vez que isso acontecer, vai haver um retry em até no máximo 4x. 
    public List<Coffee> coffees() {
        final Long invocationNumber = counter.getAndIncrement();
        /** At this point, we expose a single REST method that will show a list of coffee samples in a JSON format. 
 * Note that we introduced some fault making code in our CoffeeResource#maybeFail() method, which is going to cause failures in the CoffeeResource#coffees() endpoint method in about 50 % of requests.*/
        //Quais requests devem ser feitos? GET /coffee --> produz um JSON com a lista do map de cafés

        maybeFail(String.format("CoffeeResource#coffees() invocation #%d failed", invocationNumber));

        LOGGER.infof("CoffeeResource#coffees() invocation #%d returning successfully", invocationNumber);
        return coffeeRepository.getAllCoffees();
    }

    private void maybeFail(String failureLogMessage) {
        if (new Random().nextBoolean()) {
            LOGGER.error(failureLogMessage);
            throw new RuntimeException("Resource failure.");
        }
    }

    //Adicionando a resiliência de Timeout
    /**We added some new functionality. 
     * We want to be able to recommend some related coffees based on a coffee that a user is currently looking at. 
     * It’s not a critical functionality, it’s a nice-to-have. 
     * When the system is overloaded and the logic behind obtaining recommendations takes too long to execute, we would rather time out and render the UI without recommendations. */
    @GET
    @Path("/{id}/recommendations")
    @Timeout(250) //Note that the timeout was configured to 250 ms, and a random artificial delay between 0 and 500 ms was introduced into the CoffeeResource#recommendations() method.
    @Fallback(fallbackMethod = "fallbackRecommendations")
    public List<Coffee> recommendations(int id) {
        long started = System.currentTimeMillis();
        final long invocationNumber = counter.getAndIncrement();

        try {
            randomDelay();
            LOGGER.infof("CoffeeResource#recommendations() invocation #%d returning successfully", invocationNumber);
            return coffeeRepository.getRecommendations(id);
        } catch (InterruptedException e) {
            LOGGER.errorf("CoffeeResource#recommendations() invocation #%d timed out after %d ms",
                    invocationNumber, System.currentTimeMillis() - started);
            return null;
        }
    }
    /**In your browser, go to http://localhost:8080/coffee/2/recommendations and hit refresh a couple of times.
    You should see some requests time out with org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException. 
    Requests that do not time out should show two recommended coffee samples in JSON. */

    private void randomDelay() throws InterruptedException {
        Thread.sleep(new Random().nextInt(500));
    }
    //Add a fallback method to CoffeeResource and a @Fallback annotation to CoffeeResource#recommendations() method
    public List<Coffee> fallbackRecommendations(int id) {
        LOGGER.info("Falling back to RecommendationResource#fallbackRecommendations()");
        // safe bet, return something that everybody likes
        return Collections.singletonList(coffeeRepository.getCoffeeById(1));
    }

    /**Hit refresh several times on http://localhost:8080/coffee/2/recommendations. 
     * The TimeoutException should not appear anymore. 
     * Instead, in case of a timeout, the page will display a single recommendation that we hardcoded in our fallback method fallbackRecommendations(), rather than two recommendations returned by the original method. */
     //nota: depois da adição dessa resiliência de fallback, retorna apenas um café da lista! às vezes retornam 2, mas sempre o 1 ou 3. Por qual motivo?
    //TODO: Mais uma vez tirar dúvida com o professor de como ver o server output

    //Adicionando o Circuit Breaker
    /**A circuit breaker is useful for limiting number of failures happening in the system, when part of the system becomes temporarily unstable. 
     * The circuit breaker records successful and failed invocations of a method, and when the ratio of failed invocations reaches the specified threshold, the circuit breaker opens and blocks all further invocations of that method for a given time. */
    //O código do circuit breaker será adicionado no arquivo CoffeeRepositoryService e abaixo:
    @Path("/{id}/availability")
    @GET
    public Response availability(int id) {
        final Long invocationNumber = counter.getAndIncrement();

        Coffee coffee = coffeeRepository.getCoffeeById(id);
        // check that coffee with given id exists, return 404 if not
        if (coffee == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            Integer availability = coffeeRepository.getAvailability(coffee);
            LOGGER.infof("CoffeeResource#availability() invocation #%d returning successfully", invocationNumber);
            return Response.ok(availability).build();
        } catch (RuntimeException e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.errorf("CoffeeResource#availability() invocation #%d failed: %s", invocationNumber, message);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(message)
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .build();
        }
    }
    /**We added another functionality - the application can return the amount of remaining packages of given coffee on our store (just a random number).
This time an artificial failure was introduced in the CDI bean: the CoffeeRepositoryService#getAvailability() method is going to alternate between two successful and two failed invocations.
We also added a @CircuitBreaker annotation with requestVolumeThreshold = 4. CircuitBreaker.failureRatio is by default 0.5, and CircuitBreaker.delay is by default 5 seconds. That means that a circuit breaker will open when 2 of the last 4 invocations failed, and it will stay open for 5 seconds.

To test this out, do the following:

1.Go to http://localhost:8080/coffee/2/availability in your browser. You should see a number being returned.
2.Hit refresh, this second request should again be successful and return a number.
3.Refresh two more times. Both times you should see text "RuntimeException: Service failed.", which is the exception thrown by CoffeeRepositoryService#getAvailability().
4.Refresh a couple more times. Unless you waited too long, you should again see exception, but this time it’s "CircuitBreakerOpenException: getAvailability". This exception indicates that the circuit breaker opened and the CoffeeRepositoryService#getAvailability() method is not being called anymore.
5.Give it 5 seconds during which circuit breaker should close, and you should be able to make two successful requests again. */

}

