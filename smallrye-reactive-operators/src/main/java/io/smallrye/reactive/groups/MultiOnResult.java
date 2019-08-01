package io.smallrye.reactive.groups;

import io.smallrye.reactive.Multi;
import io.smallrye.reactive.Uni;
import io.smallrye.reactive.operators.*;
import org.reactivestreams.Publisher;

import java.util.function.*;

import static io.smallrye.reactive.helpers.ParameterValidation.nonNull;

public class MultiOnResult<T> {

    private final Multi<T> upstream;

    public MultiOnResult(Multi<T> upstream) {
        this.upstream = nonNull(upstream, "upstream");
    }


    /**
     * Produces a new {@link Multi} invoking the given function for each result emitted by the upstream {@link Multi}.
     * <p>
     * The function receives the received result as parameter, and can transform it. The returned object is sent
     * downstream as {@code result} event.
     * <p>
     *
     * @param mapper the mapper function, must not be {@code null}
     * @param <R>    the type of result produced by the mapper function
     * @return the new {@link Multi}
     */
    public <R> Multi<R> mapToResult(Function<? super T, ? extends R> mapper) {
        return new MultiMapOnResult<>(upstream, nonNull(mapper, "mapper"));
    }

    /**
     * Produces a new {@link Multi} invoking the given callback when a {@code result}  event is fired by the upstrea.
     *
     * @param callback the callback, must not be {@code null}
     * @return the new {@link Uni}
     */
    public Multi<T> consume(Consumer<T> callback) {
        return new MultiOnResultPeek<>(upstream, nonNull(callback, "callback"));
    }

    /**
     * Produces an {@link Multi} emitting the result events based on the upstream events but casted to the target class.
     *
     * @param target the target class
     * @param <O>    the type of result emitted by the produced uni
     * @return the new Uni
     */
    public <O> Multi<O> castTo(Class<O> target) {
        nonNull(target, "target");
        return mapToResult(target::cast);
    }

    /**
     * Produces a {@link Multi} that fires results coming from the reduction of the result emitted by this current
     * {@link Multi} by the passed {@code scanner} reduction function. The produced multi emits the intermediate
     * results.
     * <p>
     * Unlike {@link #scan(BiFunction)}, this operator uses the value produced by the {@code initialStateProducer} as
     * first value.
     *
     * @param scanner the reduction {@link BiFunction}, the resulting {@link Multi} emits the results of this method.
     *                The method is called for every result emitted by this Multi.
     * @return the produced {@link Multi}
     */
    public <S> Multi<S> scan(Supplier<S> initialStateProducer, BiFunction<S, ? super T, S> scanner) {
        nonNull(scanner, "scanner");
        return new MultiScanWithInitialState<>(upstream,
                nonNull(initialStateProducer, "initialStateProducer"),
                nonNull(scanner, "scanner"));
    }

    /**
     * Produces a {@link Multi} that fires results coming from the reduction of the result emitted by this current
     * {@link Multi} by the passed {@code scanner} reduction function. The produced multi emits the intermediate
     * results.
     * <p>
     * Unlike {@link #scan(Supplier, BiFunction)}, this operator doesn't take an initial value but takes the first
     * result emitted by this {@link Multi} as initial value.
     *
     * @param scanner the reduction {@link BiFunction}, the resulting {@link Multi} emits the results of this method.
     *                The method is called for every result emitted by this Multi.
     * @return the produced {@link Multi}
     */
    public Multi<T> scan(BiFunction<T, T, T> scanner) {
        return new MultiScan<>(upstream, nonNull(scanner, "scanner"));
    }

    /**
     * Produces a {@link Multi} containing the results from {@link Publisher} produced by the {@code mapper} for each
     * result emitted by this {@link Multi}.
     * <p>
     * The operation behaves as follows:
     * <ul>
     * <li>for each result emitted by this {@link Multi}, the mapper is called and produces a {@link Publisher}
     * (potentially a {@code Multi}). The mapper must not return {@code null}</li>
     * <li>The results contained in each of the produced {@link Publisher} are then <strong>merged</strong> in the
     * produced {@link Multi}. The flatten process may interleaved results.</li>
     * </ul>
     *
     * @param mapper the {@link Function} producing {@link Publisher} / {@link Multi} for each results emitted by the
     *               upstream {@link Multi}
     * @param <O>    the type of result emitted by the {@link Publisher} produced by the {@code mapper}
     * @return the produced {@link Multi}
     */
    public <O> Multi<O> flatMap(Function<? super T, ? extends Publisher<? extends O>> mapper) {
        return flatMap().publisher(mapper).mergeResults();
    }

    /**
     * Produces a {@link Multi} containing the results from {@link Publisher} produced by the {@code mapper} for each
     * result emitted by this {@link Multi}.
     * <p>
     * The operation behaves as follows:
     * <ul>
     * <li>for each result emitted by this {@link Multi}, the mapper is called and produces a {@link Publisher}
     * (potentially a {@code Multi}). The mapper must not return {@code null}</li>
     * <li>The results contained in each of the produced {@link Publisher} are then <strong>concatenated</strong> in the
     * produced {@link Multi}. The flatten process makes sure that the results are not interleaved.
     * </ul>
     *
     * @param mapper the {@link Function} producing {@link Publisher} / {@link Multi} for each results emitted by the
     *               upstream {@link Multi}
     * @param <O>    the type of result emitted by the {@link Publisher} produced by the {@code mapper}
     * @return the produced {@link Multi}
     */
    public <O> Multi<O> concatMap(Function<? super T, ? extends Publisher<? extends O>> mapper) {
        return flatMap().publisher(mapper).concatenateResults();
    }

    /**
     * Configures a <em>flatMap</em> operation that will result into a {@link Multi}.
     * <p>
     * The operations behave as follow:
     * <ul>
     * <li>for each result emitted by this {@link Multi}, a mapper is called and produces a {@link Publisher},
     * {@link Uni} or {@link Iterable}. The mapper must not return {@code null}</li>
     * <li>The results contained in each of the produced <em>sets</em> are then <strong>merged</strong> (flatMap) or
     * <strong>concatenated</strong> (concatMap) in the returned {@link Multi}.</li>
     * </ul>
     * <p>
     * The object returned by this method lets you configure the operation such as the <em>requests</em> asked to the
     * inner {@link Publisher} (produces by the mapper), concurrency (for flatMap)...
     *
     * @return the object to configure the {@code flatMap} operation.
     */
    public MultiFlatMap<T> flatMap() {
        return new MultiFlatMap<>(upstream);
    }

    /**
     * Produces a {@link Multi} containing the results from this {@link Multi} passing the {@code predicate} test.
     *
     * @param predicate the predicate, must not be {@code null}
     * @return the produced {@link Multi}
     */
    public Multi<T> filterWith(Predicate<? super T> predicate) {
        return new MultiFilter<>(upstream, nonNull(predicate, "predicate"));
    }

    /**
     * Produces a {@link Multi} containing the results from this {@link Multi} passing the {@code tester}
     * asynchronous test. Unlike {@link #filterWith(Predicate)} is the asynchronous aspect of this method. The
     * test can be asynchronous. Note that this method preserves ordering of the results, even if the test is
     * asynchronous.
     *
     * @param tester the predicate, must not be {@code null}, must not produce {@code null}
     * @return the produced {@link Multi}
     */
    public Multi<T> testWith(Function<? super T, ? extends Uni<Boolean>> tester) {
        nonNull(tester, "tester");
        return flatMap().multi(res -> {
            Uni<Boolean> uni = tester.apply(res);
            return uni.map(pass -> {
                if (pass) {
                    return res;
                } else {
                    return null;
                }
            }).toMulti();
        }).concatenateResults();
    }


}
