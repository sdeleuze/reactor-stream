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

import java.util.function.Consumer;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Loopback;
import reactor.core.state.Backpressurable;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.util.EmptySubscription;

/**
 * Create a Processor decorated with Stream API
 *
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */
public class FluxionProcessor<E, O> extends Fluxion<O> implements Processor<E, O>, Loopback {

	protected final Subscriber<E> receiver;
	protected final Publisher<O> publisher;

	protected FluxionProcessor(Subscriber<E> receiver, Publisher<O> publisher) {
		this.receiver = receiver;
		this.publisher = publisher;
	}

	/**
	 * Prepare a {@link SignalEmitter} and pass it to {@link #onSubscribe(Subscription)} if the autostart flag is
	 * set to true.
	 *
	 * @return a new {@link SignalEmitter}
	 */
	public SignalEmitter<E> bindEmitter(boolean autostart) {
		return SignalEmitter.create(this, autostart);
	}

	@Override
	public Object connectedInput() {
		return receiver;
	}

	@Override
	public Object connectedOutput() {
		return publisher;
	}

	@Override
	public long getCapacity() {
		return Backpressurable.class.isAssignableFrom(publisher.getClass()) ?
				((Backpressurable) publisher).getCapacity() : -1L;
	}

	@Override
	public int getMode() {
		return 0;
	}

	@Override
	public void onComplete() {
		receiver.onComplete();
	}

	@Override
	public void onError(Throwable t) {
		receiver.onError(t);
	}

	@Override
	public void onNext(E e) {
		receiver.onNext(e);
	}

	@Override
	public void onSubscribe(Subscription s) {
		receiver.onSubscribe(s);
	}

	/**
	 * Trigger onSubscribe with a stateless subscription to signal this subscriber it can start receiving
	 * onNext, onComplete and onError calls.
	 * <p>
	 * Doing so MAY allow direct UNBOUNDED onXXX calls and MAY prevent {@link org.reactivestreams.Publisher} to subscribe this
	 * subscriber.
	 *
	 * Note that {@link org.reactivestreams.Processor} can extend this behavior to effectively start its subscribers.
	 *
	 * @return this {@link FluxionProcessor}
	 */
	public FluxionProcessor<E, O> start() {
		onSubscribe(EmptySubscription.INSTANCE);
		return this;
	}

	/**
	 * Create a {@link SignalEmitter} and attach it via {@link #onSubscribe(Subscription)}.
	 *
	 * @return a new subscribed {@link SignalEmitter}
	 */
	public SignalEmitter<E> startEmitter() {
		return bindEmitter(true);
	}

	@Override
	public void subscribe(Subscriber<? super O> s) {
		publisher.subscribe(s);
	}

	/**
	 * Create a consumer that broadcast complete signal from any accepted value.
	 *
	 * @return a new {@link Consumer} ready to forward complete signal to this fluxion
	 * @since 2.0
	 */
	public final Consumer<?> toCompleteConsumer() {
		return new Consumer<Object>() {
			@Override
			public void accept(Object o) {
				onComplete();
			}
		};
	}

	/**
	 * Create a consumer that broadcast error signal from any accepted value.
	 *
	 * @return a new {@link Consumer} ready to forward error to this fluxion
	 * @since 2.0
	 */
	public final Consumer<Throwable> toErrorConsumer() {
		return new Consumer<Throwable>() {
			@Override
			public void accept(Throwable o) {
				onError(o);
			}
		};
	}

	/**
	 * Create a consumer that broadcast next signal from accepted values.
	 *
	 * @return a new {@link Consumer} ready to forward values to this fluxion
	 * @since 2.0
	 */
	public final Consumer<E> toNextConsumer() {
		return new Consumer<E>() {
			@Override
			public void accept(E o) {
				onNext(o);
			}
		};
	}

	@Override
	public String toString() {
		return "{" +
				"receiver: " + receiver +
				", publisher: " + publisher +
				'}';
	}
}
