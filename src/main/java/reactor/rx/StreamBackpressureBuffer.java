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

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.flow.Fuseable;
import reactor.core.util.PlatformDependent;

/**
 * @author Stephane Maldini
 */
final class StreamBackpressureBuffer<O> extends StreamSource<O, O> implements Fuseable {

	public StreamBackpressureBuffer(Publisher<? extends O> source) {
		super(source);
	}

	@Override
	public void subscribe(Subscriber<? super O> s) {
		Processor<O, O> emitter = new UnicastProcessor<O>(new SpscLinkedArrayQueue<>(PlatformDependent.SMALL_BUFFER_SIZE));
		emitter.subscribe(s);
		source.subscribe(emitter);
	}

}
