/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Receiver;
import reactor.core.state.Backpressurable;
import reactor.core.timer.Timer;
import reactor.core.util.Exceptions;
import reactor.core.util.ReactiveStateUtils;
import reactor.fn.Function;

/**
 *
 * A connecting {@link Stream} Publisher (right-to-left from a composition chain perspective)
 *
 * @param <I> Upstream type
 * @param <O> Downstream type
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public class StreamSource<I, O> extends Stream<O>
		implements Receiver,
		           Function<Subscriber<? super O>, Subscriber<? super I>> {

	final protected Publisher<? extends I> source;

	/**
	 * Unchecked wrap of {@link Publisher} as {@link Stream}, supporting {@link Fuseable} sources
	 *
	 * @param source the {@link Publisher} to wrap 
	 * @param <I> input upstream type
	 * @return a wrapped {@link Stream}
	 */
	public static <I> Stream<I> wrap(Publisher<? extends I> source){
		if(source instanceof Fuseable){
			return new FuseableStreamSource<>(source);
		}
		return new StreamSource<>(source);
	}


	public StreamSource(Publisher<? extends I> source) {
		this.source = Objects.requireNonNull(source);
	}

	@Override
	public void subscribe(Subscriber<? super O> s) {
		if (s == null) {
			throw Exceptions.argumentIsNullException();
		}
		source.subscribe(apply(s));
	}

	@Override
	public long getCapacity() {
		return Backpressurable.class.isAssignableFrom(source.getClass()) ? ((Backpressurable) source).getCapacity() :
				-1L;
	}

	@Override
	public long getPending() {
		return Backpressurable.class.isAssignableFrom(source.getClass()) ? ((Backpressurable) source).getPending() :
				-1L;
	}

	@Override
	public Timer getTimer() {
		return Stream.class.isAssignableFrom(source.getClass()) ? ((Stream) source).getTimer() : super.getTimer();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Subscriber<? super I> apply(Subscriber<? super O> subscriber) {
		return (Subscriber<I>)subscriber;
	}

	@Override
	public final Publisher<? extends I> upstream() {
		return source;
	}

	@Override
	public String toString() {
		return "{" +
				"source: " + source.toString() +
				'}';
	}

	final static class Operator<I, O> extends StreamSource<I, O> {

		private final Function<Subscriber<? super O>, Subscriber<? super I>> barrierProvider;

		public Operator(Publisher<I> source, Function<Subscriber<? super O>, Subscriber<? super I>> barrierProvider) {
			super(source);
			this.barrierProvider = barrierProvider;
		}

		@Override
		public String getName() {
			return ReactiveStateUtils.getName(barrierProvider)
			                         .replaceAll("Stream|Publisher|Operator", "");
		}
		@Override
		public Subscriber<? super I> apply(Subscriber<? super O> subscriber) {
			return barrierProvider.apply(subscriber);
		}
	}

	static final class FuseableStreamSource<I> extends StreamSource<I, I> implements Fuseable{
		public FuseableStreamSource(Publisher<? extends I> source) {
			super(source);
		}
	}
}
