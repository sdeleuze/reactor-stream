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

import reactor.fn.Consumer;

/**
 * The abstract base class for connectable publishers that let subscribers pile up
 * before they connect to their data source.
 * 
 * @param <T> the input and output value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public abstract class ConnectableStream<T> extends Stream<T> {

	/**
	 * Connects this Publisher to its source and sends a Runnable to a callback that
	 * can be used for disconnecting.
	 * <p>The call should be idempotent in respect of connecting the first
	 * and subsequent times. In addition the disconnection should be also tied
	 * to a particular connection (so two different connection can't disconnect the other).
	 * 
	 * @param cancelSupport the callback is called with a Runnable instance that can
	 * be called to disconnect the source, even synchronously.
	 */
	public abstract void connect(Consumer<? super Runnable> cancelSupport);
	
	/**
	 * Connect this Publisher to its source and return a Runnable that
	 * can be used for disconnecting.
	 * @return the Runnable that allows disconnecting the connection after.
	 */
	public final Runnable connect() {
		final Runnable[] out = { null };
		connect(new Consumer<Runnable>() {
			@Override
			public void accept(Runnable r) {
				out[0] = r;
			}
		});
		return out[0];
	}

	/**
	 *
	 * @return
	 */
	public final Stream<T> refCount() {
		return refCount(1);
	}

	/**
	 *
	 * @param minSubscribers
	 * @return
	 */
	public final Stream<T> refCount(int minSubscribers) {
		return new StreamRefCount<>(this, minSubscribers);
	}

	/**
	 *
	 * @return
	 */
	public final Stream<T> autoConnect() {
		return autoConnect(1);
	}
	
	public final Stream<T> autoConnect(int minSubscribers) {
		return autoConnect(minSubscribers, NOOP_DISCONNECT);
	}

	/**
	 *
	 * @param minSubscribers
	 * @param cancelSupport
	 * @return
	 */
	public final Stream<T> autoConnect(int minSubscribers, Consumer<? super Runnable> cancelSupport) {
		if (minSubscribers == 0) {
			connect(cancelSupport);
			return this;
		}
		return new StreamAutoConnect<>(this, minSubscribers, cancelSupport);
	}

	static final Consumer<Runnable> NOOP_DISCONNECT = new Consumer<Runnable>() {
		@Override
		public void accept(Runnable runnable) {

		}
	};
}
