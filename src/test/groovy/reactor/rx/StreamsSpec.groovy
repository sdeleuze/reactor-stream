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
package reactor.rx

import org.reactivestreams.Publisher
import org.reactivestreams.Subscription
import reactor.core.publisher.EmitterProcessor
import reactor.core.publisher.Mono
import reactor.core.publisher.SchedulerGroup
import reactor.core.subscriber.SubscriberWithContext
import reactor.core.test.TestSubscriber
import reactor.core.timer.Timer
import reactor.core.util.ReactiveStateUtils
import reactor.fn.BiFunction
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

import static reactor.core.publisher.Flux.error

class StreamsSpec extends Specification {

	@Shared
	SchedulerGroup asyncGroup

	void setupSpec() {
		Timer.global()
		asyncGroup = SchedulerGroup.async("stream-spec", 128, 4, null, null, false)
	}

	def cleanupSpec() {
		Timer.unregisterGlobal()
		asyncGroup.shutdown()
		asyncGroup = null
	}

	def 'A deferred Stream with an initial value makes that value available immediately'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test')

		when:
			'the value is retrieved'
			def value = stream.tap()
			value.subscribe()

		then:
			'it is available'
			value.get() == 'test'
	}

	def 'A deferred Stream with an initial value makes that value available once if broadcasted'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test').multicast().autoConnect()

		when:
			'the value is retrieved'
			def value = stream.tap()
			value.subscribe()
			def value2 = stream.tap()
			value2.subscribe()

		then:
			'it is available in value 1 but value 2 has subscribed after dispatching'
			value.get() == 'test'
			!value2.get()
	}

	def 'A Stream can propagate the error using await'() {
		given:
			'a composable with no initial value'
			def stream = Broadcaster.create()

		when:
			'the error is retrieved after 2 sec'
		stream.dispatchOn(asyncGroup).timeout(2, TimeUnit.SECONDS).next().get()

		then:
			'an error has been thrown'
			thrown RuntimeException
	}

	def 'A deferred Stream with an initial value makes that value available later'() {
		given:
			'a composable with an initial value'
			Stream stream = Stream.just('test')

		when:
			'the value is not retrieved'
			def value = ""
			def controls = stream.doOnNext { value = it }.log().subscribeLater()

		then:
			'it is not available'
			!value

		when:
			'the value is retrieved'
			controls.requestAll()

		then:
			'it is not available'
			value == 'test'
	}

	def 'A deferred Stream with an initial value makes that value available later up to Long.MAX '() {
		given:
			'a composable with an initial value'
			def e = null
			def latch = new CountDownLatch(1)
			def stream = Stream.fromIterable([1, 2, 3])
					.multicast()
					.autoConnect()
					.doOnError(Throwable) { e = it }
					.doOnComplete { latch.countDown() }

		when:
			'cumulated request of Long MAX'
			long test = Long.MAX_VALUE / 2l
			def controls = stream.subscribeLater()
			controls.request(test)
			controls.request(test)
			controls.request(1)

			//sleep(2000)

		then:
			'no error available'
			latch.await(2, TimeUnit.SECONDS)
			!e
	}

	def 'A deferred Stream with initial values can be consumed multiple times'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test', 'test2', 'test3').map { it }.log()

		when:
			'the value is retrieved'
			def value1 = stream.toList().get()
			def value2 = stream.toList().get()

		then:
			'it is available'
			value1 == value2
	}

	def 'A deferred Stream can filter terminal states'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test')

		when:
			'the complete signal is observed and stream is retrieved'
			def tap = stream.after()

		then:
			'it is available'
			!tap.get()

		when:
			'the error signal is observed and stream is retrieved'
			stream = Stream.error(new Exception())
			stream.after().get()

		then:
			'it is available'
			thrown Exception
	}

	def 'A deferred Stream can listen for terminal states'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test')

		when:
			'the complete signal is observed and stream is retrieved'
			def value = null

			def tap = stream.doAfterTerminate {
				println 'test'
				value = true
			}.tap()

			tap.subscribe()

			println tap.debug()

		then:
			'it is available'
			value
	}

	def 'A deferred Stream can be translated into a list'() {
		given:
			'a composable with an initial value'
			Stream stream = Stream.just('test', 'test2', 'test3')

		when:
			'the stream is retrieved'
			def value = stream.map { it + '-ok' }.toList()

			println value.debug()

		then:
			'it is available'
			value.get() == ['test-ok', 'test2-ok', 'test3-ok']
	}

	def 'A deferred Stream can be translated into a completable queue'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test', 'test2', 'test3').log().dispatchOn(asyncGroup)

		when:
			'the stream is retrieved'
			stream = stream.map { it + '-ok' }.log()

			def queue = stream.toIterable().iterator()
			//	println stream.debug()

			def res
			def result = []

			while (queue.hasNext()) {
				result << queue.next()
			}

		then:
			'it is available'
			result == ['test-ok', 'test2-ok', 'test3-ok']
	}

	def 'A deferred Stream with a generated value makes that value available immediately'() {
		given:
			String test = ""
			'a composable with an initial value'
			def stream = Stream.<String> generate{ n, s -> s.onNext test }

		when:
			'the value is retrieved'
			test = "test"
			def value = stream.tap()
			value.subscribe()

		then:
			'it is available'
			value.get() == 'test'

		when:
			'nothing is provided'
		Stream.<String> generate(null)

		then:
			"error is thrown"
			thrown(IllegalArgumentException)
	}

  def "Read Queues from Publishers"() {

	given: "Iterable publisher of 1000 to read queue"
	def pub = Stream.fromIterable(1..1000)
	def queue = pub.toIterable().iterator()

	when: "read the queue"
	def v = queue.next()
	def v2 = queue.next()
	997.times {
	  queue.next()
	}

	def v3 = queue.next()

	then: "queues values correct"
	v == 1
	v2 == 2
	v3 == 1000
  }

	def 'A Stream with a known set of values makes those values available immediately'() {
		given:
			'a composable with values 1 to 5 inclusive'
			Stream s = Stream.fromIterable([1, 2, 3, 4, 5])

		when:
			'the first value is retrieved'
			def first = s.everyFirst(5).tap()
			first.subscribe()

		and:
			'the last value is retrieved'
			def last = s.every(5).tap()
			last.subscribe()

		then:
			'first and last'
			first.get() == 1
			last.get() == 5
	}

	def 'A Stream can sample values over time'() {
		given:
			'a composable with values 1 to INT_MAX inclusive'
			def s = Stream.range(1, Integer.MAX_VALUE)

		when:
			'the most recent value is retrieved'
			def last = s
					.every(2l, TimeUnit.SECONDS)
					.publishOn(SchedulerGroup.io("work", 8, 4))
					.dispatchOn(asyncGroup)
					.log()
					.take(1)
					.next()

		then:
			last.get(5, TimeUnit.SECONDS) > 20_000
	}

	def 'A Stream can sample values over time with consumeOn'() {
		given:
			'a composable with values 1 to INT_MAX inclusive'
			def s = Stream.range(1, Integer.MAX_VALUE)

		when:
			'the most recent value is retrieved'
			def last =
			s
					.take(4, TimeUnit.SECONDS)
					.publishOn(SchedulerGroup.io("work", 8, 4))
					.last()
					.subscribeWith(Promise.ready())

		then:
			last.await() > 20_000
	}

	def 'A Stream can be enforced to dispatch values distinct from their immediate predecessors'() {
		given:
			'a composable with values 1 to 3 with duplicates'
			Stream s = Stream.fromIterable([1, 1, 2, 2, 3])

		when:
			'the values are filtered and result is collected'
			def tap = s.distinctUntilChanged().buffer().tap()
			tap.subscribe()

		then:
			'collected must remove duplicates'
			tap.get() == [1, 2, 3]
	}

	def 'A Stream can be enforced to dispatch values with keys distinct from their immediate predecessors keys'() {
		given:
			'a composable with values 1 to 5 with duplicate keys'
			Stream s = Stream.fromIterable([2, 4, 3, 5, 2, 5])

		when:
			'the values are filtered and result is collected'
			def tap = s.distinctUntilChanged { it % 2 == 0 }.buffer().tap()
			tap.subscribe()

		then:
			'collected must remove duplicates'
			tap.get() == [2, 3, 2, 5]
	}

	def 'A Stream can be enforced to dispatch distinct values'() {
		given:
			'a composable with values 1 to 4 with duplicates'
			Stream s = Stream.fromIterable([1, 2, 3, 1, 2, 3, 4])

		when:
			'the values are filtered and result is collected'
			def tap = s.distinct().buffer().tap()
			tap.subscribe()

		then:
			'collected should be without duplicates'
			tap.get() == [1, 2, 3, 4]
	}

	def 'A Stream can be enforced to dispatch values having distinct keys'() {
		given:
			'a composable with values 1 to 4 with duplicate keys'
			Stream s = Stream.fromIterable([1, 2, 3, 1, 2, 3, 4])

		when:
			'the values are filtered and result is collected'
			def tap = s.distinct { it % 3 }.buffer().tap()
			tap.subscribe()

		then:
			'collected should be without duplicates'
			tap.get() == [1, 2, 3]
	}

	def 'A Stream can check if there is a value satisfying a predicate'() {
		given:
			'a composable with values 1 to 5'
			Stream s = Stream.fromIterable([1, 2, 3, 4, 5])

		when:
			'checking for existence of values > 2 and the result of the check is collected'
			def tap = s.exists { it > 2 }.log().get()

		then:
			'collected should be true'
			tap


		when:
			'checking for existence of values > 5 and the result of the check is collected'
			tap = s.exists { it > 5 }.get()

		then:
			'collected should be false'
			!tap


		when:
			'checking always true predicate on empty stream and collecting the result'
			tap = Stream.empty().exists { true }.get();

		then:
			'collected should be false'
			!tap
	}

	def "A Stream's initial values are passed to consumers"() {
		given:
			'a composable with values 1 to 5 inclusive'
			Stream stream = Stream.fromIterable([1, 2, 3, 4, 5])

		when:
			'a Consumer is registered'
			def values = []
			stream.consume { values << it }

		then:
			'the initial values are passed'
			values == [1, 2, 3, 4, 5]


	}

	def "Stream 'state' related signals can be consumed"() {
		given:
			'a composable with values 1 to 5 inclusive'
			def stream = Stream.fromIterable([1, 2, 3, 4, 5])
			def values = []
			def signals = []

		when:
			'a Subscribe Consumer is registered'
			stream = stream.doOnSubscribe { signals << 'subscribe' }

		and:
			'a Cancel Consumer is registered'
			stream = stream.doOnCancel { signals << 'cancel' }

		and:
			'a Complete Consumer is registered'
			stream = stream.doOnComplete { signals << 'complete' }

		and:
			'the stream is consumed'
			stream.consume { values << it }

		then:
			'the initial values are passed'
			values == [1, 2, 3, 4, 5]
			'subscribe' == signals[0]
			'complete' == signals[1]
	}

	def "Stream can emit a default value if empty"() {
		given:
			'a composable that only completes'
			def stream = Stream.<String> empty()
			def values = []

		when:
			'a Subscribe Consumer is registered'
			stream = stream.defaultIfEmpty('test').doOnComplete { values << 'complete' }

		and:
			'the stream is consumed'
			stream.consume { values << it }

		then:
			'the initial values are passed'
			values == ['test', 'complete']
	}

	def 'Accepted values are passed to a registered Consumer'() {
		given:
			'a composable with a registered consumer'
			def composable = Broadcaster.<Integer> create()
			def value = composable.tap()
			value.subscribe()

		when:
			'a value is accepted'
			composable.onNext(1)

		then:
			'it is passed to the consumer'
			value.get() == 1

		when:
			'another value is accepted'
			composable.onNext(2)

		then:
			'it too is passed to the consumer'
			value.get() == 2
	}

	def 'Accepted errors are passed to a registered Consumer'() {
		given:
			'a composable with a registered consumer of RuntimeExceptions'
			Stream composable = Broadcaster.<Integer> create()
			def errors = 0
			def tail = composable.doOnError(RuntimeException) { errors++ }.subscribe()
			println tail.debug()

		when:
			'A RuntimeException is accepted'
			composable.onError(new RuntimeException())

		then:
			'it is passed to the consumer'
			errors == 1
			thrown RuntimeException

		when:
			'A new error consumer is subscribed'
		Stream.error(new RuntimeException()).doOnError(RuntimeException) { errors++ }.subscribe()

		then:
			'it is called since publisher is in error state'
			thrown RuntimeException
			errors == 2
	}

	def 'When the accepted event is Iterable, split can iterate over values'() {
		given:
			'a composable with a known number of values'
			def d = Broadcaster.<Iterable<String>> create()
			Stream<String> composable = d.flatMap{ Stream.fromIterable(it) }

		when:
			'accept list of Strings'
			def tap = composable.tap()
			tap.subscribe()
			d.onNext(['a', 'b', 'c'])

		then:
			'its value is the last of the initial values'
			tap.get() == 'c'

	}

	def 'Last value of a batch is accessible'() {
		given:
			'a composable that will accept an unknown number of values'
			def d = Broadcaster.<Integer> create()
			def composable = d.capacity(3)

		when:
			'the expected accept count is set and that number of values is accepted'
			def tap = composable.every(3).log().tap()
			tap.subscribe()
			println composable.debug()
			d.onNext(1)
			d.onNext(2)
			d.onNext(3)

		then:
			"last's value is now that of the last value"
			tap.get() == 3

		when:
			'the expected accept count is set and that number of values is accepted'
			tap = composable.every(3).tap()
			tap.subscribe()
			d.onNext(1)
			d.onNext(2)
			d.onNext(3)

		then:
			"last's value is now that of the last value"
			tap.get() == 3
	}

	def "A Stream's values can be mapped"() {
		given:
			'a source composable with a mapping function'
			def source = Broadcaster.<Integer> create()
			Stream mapped = source.map { it * 2 }

		when:
			'the source accepts a value'
			def value = mapped.tap()
			value.subscribe()
			source.onNext(1)

		then:
			'the value is mapped'
			value.get() == 2
	}

	def "Stream's values can be exploded"() {
		given:
			'a source composable with a mapMany function'
			def source = Broadcaster.<Integer> create()
			Stream<Integer> mapped = source.
					dispatchOn(asyncGroup).
					flatMap { v -> Stream.just(v * 2) }.
					doOnError(Throwable) { it.printStackTrace() }


		when:
			'the source accepts a value'
			def value = mapped.promise()
			println source.debug()
			source.onNext(1)
			println source.debug()

		then:
			'the value is mapped'
			value.await() == 2
	}

	def "Multiple Stream's values can be merged"() {
		given:
			'source composables to merge, buffer and tap'
			def source1 = Broadcaster.<Integer> create()

			def source2 = Broadcaster.<Integer> create()
			source2.map { it }.map { it }

			def source3 = Broadcaster.<Integer> create()

			def tap = Stream.merge(source1, source2, source3).log().buffer(3).log().tap()
			tap.subscribe()

		when:
			'the sources accept a value'
			source1.onNext(1)
			source2.onNext(2)
			source3.onNext(3)

			println source1.debug()

		then:
			'the values are all collected from source1 stream'
			tap.get() == [1, 2, 3]
	}


	def "Multiple Stream's values can be zipped"() {
		given:
			'source composables to merge, buffer and tap'
			def source1 = Broadcaster.<Integer> create()
			def source2 = Broadcaster.<Integer> create()
			def zippedStream = Stream.zip(source1, source2, (BiFunction) { t1, t2 -> println t1; t1 + t2 }).log()
			def tap = zippedStream.tap()
			tap.subscribe()

		when:
			'the sources accept a value'
			source1.onNext(1)
			source2.onNext(2)
			source2.onNext(3)
			source2.onNext(4)

			println tap.debug()

		then:
			'the values are all collected from source1 stream'
			tap.get() == 3

		when:
			'the sources accept the missing value'
			source2.onNext(5)
			source1.onNext(6)

			println tap.debug()

		then:
			'the values are all collected from source1 stream'
			tap.get() == 9
	}

	def "Multiple iterable Stream's values can be zipped"() {
		given:
			'source composables to zip, buffer and tap'
			def odds = Stream.just(1, 3, 5, 7, 9)
			def even = Stream.just(2, 4, 6)

		when:
			'the sources are zipped'
			def zippedStream = Stream.zip(odds.log('left'), even.log('right'), (BiFunction) { t1, t2 -> [t1, t2] })
			def tap = zippedStream.log().toList()

		then:
			'the values are all collected from source1 stream'
			tap.get() == [[1, 2], [3, 4], [5, 6]]

		when:
			'the sources are zipped in a flat map'
			zippedStream = odds.log('before-flatmap').flatMap {
				Stream.zip(Stream.just(it), even, (BiFunction) { t1, t2 -> [t1, t2] }).log('second-fm')
			}
			tap = zippedStream.log('after-zip').toList()


		then:
			'the values are all collected from source1 stream'
			tap.get() == [[1, 2], [3, 2], [5, 2], [7, 2], [9, 2]]
	}


	def "A different way of consuming"() {
		given:
			'source composables to zip, buffer and tap'
			def odds = Stream.just(1, 3, 5, 7, 9)
			def even = Stream.just(2, 4, 6)

		when:
			'the sources are zipped'
			def mergedStream = Stream.merge(odds, even)
			def res = []
			mergedStream.consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res.sort(); res << 'done'; println 'completed!' }
			)

		then:
			'the values are all collected from source1 and source2 stream'
			res == [1, 2, 3, 4, 5, 6, 7, 9, 'done']

	}

	def "Adaptive consuming"() {
		given:
			'source iterable'
			def s = Stream.just(1, 2, 3, 4, 5, 6, 7, 8)

		when:
			'the source is consumed every in 3 times'
			def res = []
			s.log().consumeWithRequest(
					{ res << it },
					{ def i = it == 0L ? 2L : (it * 2L); res << "r:$i"; i }
			)

		then:
			'the values are all collected in 3 times'
			res == ['r:2', 1, 2, 'r:4', 3, 4, 5, 6, 'r:8', 7, 8]

	}

	def "Combine latest stream data"() {
		given:
			'source composables to zip, buffer and tap'
			def w1 = Broadcaster.<String> create()
			def w2 = Broadcaster.<String> create()
			def w3 = Broadcaster.<String> create()

		when:
			'the sources are zipped'
			def mergedStream = Stream.combineLatest(w1, w2, w3, { t -> t[0] + t[1] + t[2] })
			def res = []

			mergedStream.consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res.sort(); res << 'done'; println 'completed!' }
			)

			w1.onNext("1a")
			w2.onNext("2a")
			w3.onNext("3a")
			w1.onComplete()
			// twice for w2
			w2.onNext("2b")
			w2.onComplete()
			// 4 times for w3
			w3.onNext("3b")
			w3.onNext("3c")
			w3.onNext("3d")
			w3.onComplete()

			println mergedStream.debug()

		then:
			'the values are all collected from source1 and source2 stream'
			res == ['1a2a3a', '1a2b3a', '1a2b3b', '1a2b3c', '1a2b3d', 'done']

	}

	def "A simple concat"() {
		given:
			'source composables to zip, buffer and tap'
			def firsts = Stream.just(1, 2, 3)
			def lasts = Stream.just(4, 5)

		when:
			'the sources are zipped'
			def mergedStream = Stream.concat(firsts, lasts)
			def res = []
			mergedStream.consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res << 'done'; println 'completed!' }
			)

		then:
			'the values are all collected from source1 and source2 stream'
			res == [1, 2, 3, 4, 5, 'done']

		when:
			res = []
			lasts.startWith(firsts).consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res << 'done'; println 'completed!' }
			)

		then:
			'the values are all collected from source1 and source2 stream'
			res == [1, 2, 3, 4, 5, 'done']

		when:
			res = []
			lasts.startWith([1, 2, 3]).consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res << 'done'; println 'completed!' }
			)

		then:
			'the values are all collected from source1 and source2 stream'
			res == [1, 2, 3, 4, 5, 'done']

	}

	def "A mapped concat"() {
		given:
			'source composables to zip, buffer and tap'
			def firsts = Stream.just(1, 2, 3)

		when:
			'the sources are zipped'
			def mergedStream = firsts.concatMap { Stream.range(it, 2) }
			def res = []
			println mergedStream.consume(
					{ res << it; println it },
					{ it.printStackTrace() },
					{ res << 'done'; println 'completed!' }
			).debug()

		then:
			'the values are all collected from source1 and source2 stream'
			res == [1, 2, 2, 3, 3, 4, 'done']
	}

	def "Stream can be counted"() {
		given:
			'source composables to count and tap'
			def source = Broadcaster.<Integer> create()
			def tap = source.count().subscribeWith(Promise.prepare())

		when:
			'the sources accept a value'
			source.onNext(1)
			source.onNext(2)
			source.onNext(3)
			source.onComplete()

		then:
			'the count value matches the number of accept'
			tap.peek() == 3
	}

	def 'A Stream can return a value at a certain index'() {
		given:
			'a composable with values 1 to 5'
			def s = Stream.just(1, 2, 3, 4, 5)
			def error = 0
			def errorConsumer = { error++ }

		when:
			'element at index 2 is requested'
			def tap = s.elementAt(2).get()

		then:
			'3 is emitted'
			tap == 3

		when:
			'element with negative index is requested'
			s.elementAt(-1)

		then:
			'error is thrown'
			thrown(IndexOutOfBoundsException)

		when:
			'element with index > number of values is requested'
			s.elementAt(10).doOnError(errorConsumer).subscribe()

		then:
			'error is thrown'
			thrown IndexOutOfBoundsException
			error == 1
	}

	def 'A Stream can return a value at a certain index or a default value'() {
		given:
			'a composable with values 1 to 5'
			def s = Stream.just(1, 2, 3, 4, 5)

		when:
			'element at index 2 is requested'
			def tap = s.elementAtOrDefault(2, {-1}).get()

		then:
			'3 is emitted'
			tap == 3

		when:
			'element with index > number of values is requested'
			tap = s.elementAtOrDefault(10, {-1}).get()

		then:
			'-1 is emitted'
			tap == -1
	}

	def "A Stream's values can be filtered"() {
		given:
			'a source composable with a filter that rejects odd values'
			def source = Broadcaster.<Integer> create()
			Stream filtered = source.filter { it % 2 == 0 }

		when:
			'the source accepts an even value'
			def value = filtered.tap()
			value.subscribe()
			println value.debug()
			source.onNext(2)

		then:
			'it passes through'
			value.get() == 2

		when:
			'the source accepts an odd value'
			source.onNext(3)

		then:
			'it is blocked by the filter'
			value.get() == 2


		when:
			'simple filter'
			def anotherSource = Broadcaster.<Boolean> create()
			def tap = anotherSource.filter{ it }.tap()
			tap.subscribe()
			anotherSource.onNext(true)

		then:
			'it is accepted by the filter'
			tap.get()

		when:
			'simple filter nominal case'
			anotherSource = Broadcaster.<Boolean> create()
			tap = anotherSource.filter{ it }.tap()
			tap.subscribe()
			anotherSource.onNext(false)

		then:
			'it is not accepted by the filter'
			!tap.get()
	}

	def "When a mapping function throws an exception, the mapped composable accepts the error"() {
		given:
			'a source composable with a mapping function that throws an error'
			def source = Broadcaster.<Integer> create()
			Stream mapped = source.map { if (it == 1) throw new RuntimeException() else 'na' }
			def errors = 0
			mapped.doOnError(Exception) { errors++ }.subscribe()

		when:
			'the source accepts a value'
			source.onNext(1)
			println source.debug()

		then:
			'the error is passed on'
		errors == 1
		thrown Exception //because consume doesn't have error handler
	}

	def "When a processor is streamed"() {
		given:
			'a source composable and a async downstream'
			def source = Broadcaster.<Integer> replay()

			def res = source
					.publishOn(SchedulerGroup.io("test",32,2))
					.delaySubscription(1L)
					.map { it * 2 }
					.buffer()
					.promise()

		when:
			'the source accepts a value'
			source.onNext(1)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			source.onComplete()

		then:
			'the res is passed on'
			res.get() == [2, 4, 6, 8]
	}

	def "When a filter function throws an exception, the filtered composable accepts the error"() {
		given:
			'a source composable with a filter function that throws an error'
			def source = Broadcaster.<Integer> create()
			Stream filtered = source.filter { if (it == 1) throw new RuntimeException() else true }
			def errors = 0
			filtered.doOnError(Exception) { errors++ }.subscribe()

		when:
			'the source accepts a value'
			source.onNext(1)

		then:
			'the error is passed on'
			errors == 1
			thrown Exception //no error handler
	}

	def "A known set of values can be reduced"() {
		given:
			'a composable with a known set of values'
			Stream source = Stream.fromIterable([1, 2, 3, 4, 5])

		when:
			'a reduce function is registered'
			def reduced = source.reduce(new Reduction())
			def value = reduced.get()

		then:
			'the resulting composable holds the reduced value'
			value == 120

		when:
			'use an initial value'
			value = source.reduce(2, new Reduction()).get()

		then:
			'the updated reduction is available'
			value == 240
	}

	def "When reducing a known set of values, only the final value is passed to consumers"() {
		given:
			'a composable with a known set of values and a reduce function'
			def reduced = Stream.<Integer> just(1, 2, 3, 4, 5)
					.reduce(new Reduction())

		when:
			'a consumer is registered'
			def values = reduced.get()

		then:
			'the consumer only receives the final value'
			values == 120
	}

	def "When reducing a known number of values, only the final value is passed to consumers"() {
		given:
			'a composable with a known number of values and a reduce function'
			def source = Broadcaster.<Integer> create()
			def reduced = source.reduce(new Reduction())
			def values = []
			reduced.doOnSuccess { values << it }.subscribe()

		when:
			'the expected number of values is accepted'
			source.onNext(1)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			source.onNext(5)
			source.onComplete()
			println source.debug()
		then:
			'the consumer only receives the final value'
			values == [120]
	}

	def 'A known number of values can be reduced'() {
		given:
			'a composable that will accept 5 values and a reduce function'
			def source = Broadcaster.<Integer> create()
			def reduced = source.reduce(new Reduction())
			def value = reduced.subscribeWith(Promise.prepare())

		when:
			'the expected number of values is accepted'
			source.onNext(1)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			source.onNext(5)
			source.onComplete()

		then:
			'the reduced composable holds the reduced value'
			value.peek() == 120
	}

	def 'When a known number of values is being reduced, only the final value is made available'() {
		given:
			'a composable that will accept 2 values and a reduce function'
			def source = Broadcaster.<Integer> create()
			def value = source.reduce(new Reduction()).subscribeWith(Promise.ready())

		when:
			'the first value is accepted'
			source.onNext(1)

		then:
			'the reduced value is unknown'
			value.peek() == null

		when:
			'the second value is accepted'
			source.onNext(2)
			source.onComplete()

		then:
			'the reduced value is known'
			value.peek() == 2
	}

	def 'When an unknown number of values is being reduced, each reduction is passed to a consumer on window'() {
		given:
			'a composable with a reduce function'
			def source = Broadcaster.<Integer> create()
			def reduced = source.window(2).log().flatMap { it.log('lol').reduce(new Reduction()) }
			def value = reduced.subscribeWith(Promise.prepare())

		when:
			'the first value is accepted'
			source.onNext(1)

		then:
			'the reduction is not available'
			!value.peek()

		when:
			'the second value is accepted and flushed'
			source.onNext(2)

		then:
			'the updated reduction is available'
			value.peek() == 2
	}

	def 'When an unknown number of values is being scanned, each reduction is passed to a consumer'() {
		given:
			'a composable with a reduce function'
			def source = Broadcaster.<Integer> create()
			def reduced = source.scan(new Reduction())
			def value = reduced.tap()
			value.subscribe()

		when:
			'the first value is accepted'
			source.onNext(1)

		then:
			'the reduction is available'
			value.get() == 1

		when:
			'the second value is accepted'
			source.onNext(2)

		then:
			'the updated reduction is available'
			value.get() == 2

		when:
			'use an initial value'
			value = source.scan(4, new Reduction()).tap()
			value.subscribe()
			source.onNext(1)

		then:
			'the updated reduction is available'
			value.get() == 4
	}


	def 'Reduce will accumulate a list of accepted values'() {
		given:
			'a composable'
			def source = Broadcaster.<Integer> create()
			def reduced = source.capacity(1).buffer()
			def value = reduced.subscribeWith(Promise.prepare())

		when:
			'the first value is accepted'
			println value.debug()
			source.onNext(1)
			println value.debug()
			source.onComplete()

		then:
			'the list contains the first element'
			value.peek() == [1]
	}

	def 'Collect will accumulate a list of accepted values and pass it to a consumer'() {
		given:
			'a source and a collected stream'
			def source = Broadcaster.<Integer> create()
			Stream reduced = source.buffer(2)
			def value = reduced.subscribeWith(Promise.prepare())

		when:
			'the first value is accepted on the source'
			source.onNext(1)

		then:
			'the collected list is not yet available'
			value.peek() == null

		when:
			'the second value is accepted'
			source.onNext(2)

		then:
			'the collected list contains the first and second elements'
			value.peek() == [1, 2]
	}


	def 'Collect will accumulate multiple lists of accepted values and pass it to a consumer'() {
		given:
			'a source and a collected stream'
			def numbers = Stream.fromIterable([1, 2, 3, 4, 5, 6, 7, 8]);

		when:
			'non overlapping buffers'
			def res = numbers.buffer(2, 3).log('skip1').buffer().promise()

		then:
			'the collected lists are available'
			res.await() == [[1, 2], [4, 5], [7, 8]]

		when:
			'overlapping buffers'
			res = numbers.buffer(3, 2).log('skip2').buffer().promise()

		then:
			'the collected overlapping lists are available'
			res.await() == [[1, 2, 3], [3, 4, 5], [5, 6, 7], [7, 8]]


		when:
			'non overlapping buffers'
			res = numbers.throttleRequest(100).log('beforeBuffer').buffer(200l, 300l, TimeUnit.MILLISECONDS).log('afterBuffer').buffer().promise()

		then:
			'the collected lists are available'
			res.await(5, TimeUnit.SECONDS) == [[1, 2], [4, 5], [7, 8]]
	}


	def 'Collect will accumulate multiple lists of accepted values and pass it to a consumer on bucket close'() {
		given:
			'a source and a collected stream'
			def numbers = Broadcaster.<Integer> create();

		when:
			'non overlapping buffers'
			def boundaryStream = Broadcaster.<Integer> create()
			def res = numbers.buffer ( boundaryStream ).buffer().promise()

			numbers.onNext(1)
			numbers.onNext(2)
			numbers.onNext(3)
			boundaryStream.onNext(1)
			numbers.onNext(5)
			numbers.onNext(6)
			numbers.onComplete()

		then:
			'the collected lists are available'
			res.await() == [[1, 2, 3], [5, 6]]

		when:
			'overlapping buffers'
			def bucketOpening = Broadcaster.<Integer> create()
			boundaryStream = Broadcaster.<Integer> create()
			numbers = Broadcaster.<Integer> create()
			res = numbers.log('numb').buffer(bucketOpening) { boundaryStream.log('boundary') }.log('promise').buffer().promise()

			numbers.onNext(1)
			numbers.onNext(2)
			bucketOpening.onNext(1)
			numbers.onNext(3)
			bucketOpening.onNext(1)
			numbers.onNext(5)
			boundaryStream.onNext(1)
			bucketOpening.onNext(1)
			numbers.onNext(6)
			boundaryStream.onComplete()
			numbers.onComplete()


		then:
			'the collected overlapping lists are available'
			res.await() == [[3, 5], [5], [6]]
	}

	def 'Window will reroute multiple stream of accepted values and pass it to a consumer'() {
		given:
			'a source and a collected stream'
			def numbers = Stream.fromIterable([1, 2, 3, 4, 5, 6, 7, 8])

		when:
			'non overlapping buffers'
			def res = numbers.log('n').window(2, 3).log('test').flatMap { it.log('fm').buffer() }.buffer().promise()

		then:
			'the collected lists are available'
			res.await() == [[1, 2], [4, 5], [7, 8]]

		when:
			'overlapping buffers'
			res = numbers.log("n").window(3, 2).log('w').flatMap { it.log('b').toList() }.toList()

		then:
			'the collected overlapping lists are available'
			res.get() == [[1, 2, 3], [3, 4, 5], [5, 6, 7], [7, 8]]


		when:
			'non overlapping buffers'
			res = numbers.throttleRequest(100).window(200l, 300l, TimeUnit.MILLISECONDS).flatMap { it.log('fm').buffer() }
					.buffer().promise()

		then:
			'the collected lists are available'
			res.await() == [[1, 2], [4, 5], [7, 8]]

	}


	def 'Re route will accumulate multiple lists of accepted values and pass it to a consumer on bucket close'() {
		given:
			'a source and a collected stream'
			def numbers = Broadcaster.<Integer> create();

		when:
			'non overlapping buffers'
			def boundaryStream = Broadcaster.<Integer> create()
			def res = numbers.window(boundaryStream).flatMap { it.buffer().log() }.buffer().promise()

			numbers.onNext(1)
			numbers.onNext(2)
			numbers.onNext(3)
			boundaryStream.onNext(1)
			numbers.onNext(5)
			numbers.onNext(6)
			numbers.onComplete()

		then:
			'the collected lists are available'
			res.await() == [[1, 2, 3], [5, 6]]

		when:
			'overlapping buffers'
			numbers = Broadcaster.<Integer> create()
			def bucketOpening = Broadcaster.<Integer> create()
			res = numbers.log("w").window(bucketOpening.log("bucket")) { boundaryStream.log('boundary') }.flatMap { it
					.log('fm').buffer() }
					.buffer()
					.promise()

			numbers.onNext(1)
			numbers.onNext(2)
			bucketOpening.onNext(1)
			numbers.onNext(3)
			bucketOpening.onNext(1)
			numbers.onNext(5)
			boundaryStream.onNext(1)
			bucketOpening.onNext(1)
			numbers.onNext(6)
			numbers.onComplete()


		then:
			'the collected overlapping lists are available'
			res.await() == [[3, 5], [5], [6]]
	}

	def 'Window will re-route N elements to a fresh nested stream'() {
		given:
			'a source and a collected window stream'
			def source = Broadcaster.<Integer> create()
			def value = null

			source.log('w').window(2).consume {
				value = it.log().buffer(2).tap()
			  	value.subscribe()
			}


		when:
			'the first value is accepted on the source'
			source.onNext(1)

		then:
			'the collected list is not yet available'
			value
			value.get() == null

		when:
			'the second value is accepted'
			source.onNext(2)
			println value.debug()

		then:
			'the collected list contains the first and second elements'
			value.get() == [1, 2]

		when:
			'2 more values are accepted'
			source.onNext(3)
			source.onNext(4)
			println value.debug()

		then:
			'the collected list contains the first and second elements'
			value.get() == [3, 4]
	}

	def 'Window will re-route N elements over time to a fresh nested stream'() {
		given:
			'a source and a collected window stream'
			def source = Broadcaster.<Integer> create()
			def promise = Promise.ready()

			source.dispatchOn(asyncGroup).log("prewindow").window(10l, TimeUnit.SECONDS).consume {
				it.log().buffer(2).consume { promise.onNext(it) }
			}


		when:
			'the first value is accepted on the source'
			source.onNext(1)
			println source.debug()

		then:
			'the collected list is not yet available'
			promise.peek() == null

		when:
			'the second value is accepted'
			source.onNext(2)
			println source.debug()

		then:
			'the collected list contains the first and second elements'
			promise.await() == [1, 2]

		when:
			'2 more values are accepted'
			promise = Promise.ready()

			sleep(2000)
			source.onNext(3)
			source.onNext(4)
			println source.debug()

		then:
			'the collected list contains the first and second elements'
			promise.await() == [3, 4]
	}

	def 'GroupBy will re-route N elements to a nested stream based on the mapped key'() {
		given:
			'a source and a grouped by ID stream'
			def source = Broadcaster.<SimplePojo> create()
			def result = [:]

			source.groupBy { pojo ->
				pojo.id
			}.consume { stream ->
				stream.consume { pojo ->
					if (result[pojo.id]) {
						result[pojo.id] << pojo.title
					} else {
						result[pojo.id] = [pojo.title]
					}
				}
			}

			println source.debug()

		when:
			'some values are accepted'
			source.onNext(new SimplePojo(id: 1, title: 'Stephane'))
			source.onNext(new SimplePojo(id: 1, title: 'Jon'))
			source.onNext(new SimplePojo(id: 1, title: 'Sandrine'))
			source.onNext(new SimplePojo(id: 2, title: 'Acme'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme2'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme3'))
			source.onComplete()

		then:
			'the result should group titles by id'
			result
			result == [
					1: ['Stephane', 'Jon', 'Sandrine'],
					2: ['Acme'],
					3: ['Acme2', 'Acme3']
			]
	}

	def 'GroupBy will re-route N elements to a nested stream based on hashcode'() {
		given:
			'a source and a grouped by ID stream'
			def source = Broadcaster.<SimplePojo> create()
			def result = [:]
			def latch = new CountDownLatch(6)

			def partitionStream = source.dispatchOn(asyncGroup).partition()
			partitionStream.consume { stream ->
				stream.cast(SimplePojo).consume { pojo ->
					if (result[pojo.id]) {
						result[pojo.id] << pojo.title
					} else {
						result[pojo.id] = [pojo.title]
					}
					latch.countDown()
				}
			}

			println source.debug()
	  println ReactiveStateUtils.prettyPrint(source)

		when:
			'some values are accepted'
			source.onNext(new SimplePojo(id: 1, title: 'Stephane'))
			source.onNext(new SimplePojo(id: 1, title: 'Jon'))
			source.onNext(new SimplePojo(id: 1, title: 'Sandrine'))
			source.onNext(new SimplePojo(id: 2, title: 'Acme'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme2'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme3'))


		then:
			'the result should group titles by id'
			latch.await(5, TimeUnit.SECONDS)
			result
			result == [
					1: ['Stephane', 'Jon', 'Sandrine'],
					2: ['Acme'],
					3: ['Acme2', 'Acme3']
			]
	}

	@Ignore
	def 'StreamUtils will parse a Stream to a Map'() {
		given:
			'a source and a grouped by ID stream'
			def source = Broadcaster.<SimplePojo> create()

			source.groupBy { pojo ->
				pojo.id
			}.consume { stream ->
				stream.consume { pojo ->

				}
			}

		when:
			'some values are accepted'
			source.onNext(new SimplePojo(id: 1, title: 'Stephane'))
			source.onNext(new SimplePojo(id: 1, title: 'Jon'))
			source.onNext(new SimplePojo(id: 1, title: 'Sandrine'))
			source.onNext(new SimplePojo(id: 2, title: 'Acme'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme2'))
			source.onNext(new SimplePojo(id: 3, title: 'Acme3'))
			println source.debug()
			def result = source.debug()


		then:
			'the result should contain all stream titles by id'
			result.nodes.to[0].id == "GroupBy"
			result.to[0].to[0].id == "Consumer"
			result.to[0].boundTo[0].id == "Consumer"
			result.to[0].boundTo[1].id == "Consumer"
			result.to[0].boundTo[2].id == "Consumer"

		when: "complete will cancel non kept-alive actions"
			source.onComplete()
			result = source.debug().toMap()
			println source.debug()

		then:
			'the result should contain zero stream'
			!result.to
	}

	def 'Creating Stream from publisher'() {
		given:
			'a source stream with a given publisher'
			def s = Stream.<String> yield {
				it.onNext('test1')
				it.onNext('test2')
				it.onNext('test3')
				it.onComplete()
			}.log()

		when:
			'accept a value'
			def result = s.toList().get(5, TimeUnit.SECONDS)
			//println s.debug()

		then:
			'dispatching works'
			result == ['test1', 'test2', 'test3']
	}

	def 'Creating Stream from publisher factory'() {
		given:
			'a source stream with a given publisher'
			def s = Stream.generate(
					{ long n, SubscriberWithContext<String, Void> sub ->
						(1..3).each {
							sub.onNext("test$it")
						}
						sub.onComplete()
					},
					{ println Thread.currentThread().name + ' start' },
					{ println Thread.currentThread().name + ' end' }
			)
					.log()
					.publishOn(SchedulerGroup.io("work", 8, 4))

		when:
			'accept a value'
			def result = s.toList().get(5, TimeUnit.SECONDS)
			//println s.debug()

		then:
			'dispatching works'
			result == ['test1', 'test2', 'test3']
	}


	def 'Defer Stream from publisher'() {
		given:
			'a source stream with a given publisher factory'
			def i = 0
			def res = []
			def s = Stream.<Integer> defer {
				Stream.just(i++)
			}.log()

		when:
			'accept a value'
			res << s.next().get(5, TimeUnit.SECONDS)
			res << s.next().get(5, TimeUnit.SECONDS)
			res << s.next().get(5, TimeUnit.SECONDS)
			//println s.debug()

		then:
			'dispatching works'
			res == [0, 1, 2]
	}

	def 'Wrap Stream from publisher'() {
		given:
			'a source stream with a given publisher factory'
			def terminated = false
			def s = Stream.<Integer> from {
				it.onSubscribe(new Subscription() {
					@Override
					void request(long n) {
						it.onNext(1)
						it.onNext(2)
						it.onNext(3)
						it.onComplete()
					}

					@Override
					void cancel() {
					}
				})
			}.log()

		when:
			'accept a value'
			def res = s.toList().get(5, TimeUnit.SECONDS)
			//println s.debug()

		then:
			'dispatching works'
			res == [1, 2, 3]
	}

	def 'Wrap Stream from processor'() {
		given:
			'a source stream with a given publisher factory'
			def emitter = EmitterProcessor.create()
			emitter.start()
			def s = Stream.fromProcessor(emitter)

		when:
			'accept a value'
			def res = s.log().buffer().promise()
			s.onNext 1
			s.onNext 2
			s.onNext 3
			s.onComplete()
			res.await(5, TimeUnit.SECONDS)
			//println s.debug()

		then:
			'dispatching works'
			res.peek() == [1, 2, 3]
	}

	def 'Creating Stream from Timer'() {
		given:
			'a source stream with a given globalTimer'

			def res = 0l
			def c = Mono.delay(1)
			def timeStart = System.currentTimeMillis()

		when:
			'consuming'
			c.get()
			res = System.currentTimeMillis() - timeStart

		then:
			'ready'
			res > 900

		when:
			'consuming periodic'
			def i = []
			c = Stream.interval(0, 1).log().consume {
				i << it
			}
			sleep(2500)
			c.cancel()

		then:
			'ready'
			i.containsAll([0l, 1l])
	}


	def 'Creating Stream from range'() {
		given:
			'a source stream with a given range'
			def s = Stream.range(1, 10)

		when:
			'accept a value'
			def result = s.toList().get()

		then:
			'dispatching works'
			result == [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
	}

	def 'Counting Stream from range'() {
		given:
			'a source stream with a given range'
			def s = Stream.range(1, 10)

		when:
			'accept a value'
			def result = s.count().get()

		then:
			'dispatching works'
			result == 10
	}

	def 'Creating Empty Streams from publisher'() {
		given:
			'a source stream pre-completed'
			def s = Stream.empty()

		when:
			'consume it'
			def latch = new CountDownLatch(1)
			def nexts = []
			def errors = []

			s.consume(
					{ nexts << 'never' },
					{ errors << 'never ever' },
					{ latch.countDown() }
			)

		then:
			'dispatching works'
			latch.await(2, TimeUnit.SECONDS)
			!nexts
			!errors
	}

	def 'Caching Stream from publisher'() {
		given:
			'a slow source stream'
			def s = Stream.<Integer> yield {
				it.submit 1
				sleep 200
				it.submit 2
				sleep 200
				it.submit 3
				it.finish()
			}.log('beforeCache')
					.cache()
					.log('afterCache')
					.elapsed()
					.map { it.t1 }

		when:
			'consume it twice'
			def latch = new CountDownLatch(2)
			def nexts = []
			def errors = []

			s.consume(
					{ nexts << it },
					{ errors << it },
					{ latch.countDown() }
			)

			s.consume(
					{ nexts << it },
					{ errors << it },
					{ latch.countDown() }
			)

		then:
			'dispatching works'
			latch.await(2, TimeUnit.SECONDS)
			!errors
			nexts.size() == 6
			nexts[0] + nexts[1] + nexts[2] >= 400
			nexts[3] + nexts[4] + nexts[5] < 50
	}

	def 'Creating Streams from future'() {
		given:
			'a source stream pre-completed'
			def executorService = Executors.newSingleThreadExecutor()

			def future = executorService.submit({
				'hello future'
			} as Callable<String>)

			def s = Stream.fromFuture(future).as{ Stream.from(it) }

		when:
			'consume it'
			def latch = new CountDownLatch(1)
			def nexts = []
			def errors = []

			s.consume(
					{ nexts << it },
					{ errors << it; it.printStackTrace() },
					{ latch.countDown() }
			)
			def counted = latch.await(3, TimeUnit.SECONDS)
		then:
			'dispatching works'
			!errors
			nexts[0] == 'hello future'
			counted

		when: 'timeout'
			latch = new CountDownLatch(1)

			future = executorService.submit({
				sleep(2000)
				'hello future too long'
			} as Callable<String>)

			s = Stream.fromFuture(future, 100, TimeUnit.MILLISECONDS).dispatchOn(asyncGroup).as{ Stream.from(it) }
			nexts = []
			errors = []

			s.consume(
					{ nexts << 'never ever' },
					{ errors << it; latch.countDown() }
			)

		then:
			'error dispatching works'
			latch.await(2, TimeUnit.SECONDS)
			errors[0] in TimeoutException
			!nexts

		cleanup:
			executorService.shutdown()
	}

	def 'Throttle will accumulate a list of accepted values and pass it to a consumer on the specified period'() {
		given:
			'a source and a collected stream'
			def source = Broadcaster.<Integer> create()
			def reduced = source.buffer(2).log().throttleRequest(300)
			def value = reduced.tap()
			value.subscribe()

		when:
			'the first values are accepted on the source'
			source.onNext(1)
			println source.debug()
			source.onNext(2)
			sleep(1200)
			println source.debug()

		then:
			'the collected list is available'
			value.get() == [1, 2]

		when:
			'the second value is accepted'
			source.onNext(3)
			source.onNext(4)
			sleep(1200)

		then:
			'the collected list contains the first and second elements'
			value.get() == [3, 4]
	}

	def 'Throttle will generate demand every specified period'() {
		given:
			'a source and a collected stream'
			def random = new Random()
			def source = Stream.yield {
				it.submit random.nextInt()
			}.dispatchOn(asyncGroup)

			def values = []

			source.throttleRequest(200).consume {
				values << it
			}

		when:
			'the first values are accepted on the source'
			sleep(1200)

		then:
			'the collected list is available'
			values

	}

	def 'Collect with Timeout will accumulate a list of accepted values and pass it to a consumer'() {
		given:
			'a source and a collected stream'
			def source = Broadcaster.<Integer> create()
			def reduced = source.buffer(5, 600, TimeUnit.MILLISECONDS)
			def value = reduced.tap()
			value.subscribe()
			println value.debug()

		when:
			'the first values are accepted on the source'
			source.onNext(1)
			source.onNext(1)
			source.onNext(1)
			source.onNext(1)
			source.onNext(1)
			println value.debug()
			sleep(2000)
			println value.debug()

		then:
			'the collected list is not yet available'
			value.get() == [1, 1, 1, 1, 1]

		when:
			'the second value is accepted'
			source.onNext(2)
			source.onNext(2)
			sleep(2000)
			println value.debug()

		then:
			'the collected list contains the first and second elements'
			value.get() == [2, 2]

	}

	def 'Timeout can be bound to a stream'() {
		given:
			'a source and a timeout'
			def source = Broadcaster.<Integer> create()
			def reduced = source.timeout(1500, TimeUnit.MILLISECONDS)
			def error = null
			def value = reduced.onErrorResumeWith(){
				error = it
			  Stream.just(-5)
			}.tap()
			value.subscribe()
			println value.debug()

		when:
			'the first values are accepted on the source, paused just enough to refresh globalTimer until 6'
			source.onNext(1)
			sleep(500)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			sleep(500)
			source.onNext(5)
			sleep(2000)
			source.onNext(6)
			println error

		then:
			'last value known is 5 and stream is in error state'
			error in TimeoutException
			value.get() == -5
	}

	def 'Timeout can be bound to a stream and fallback'() {
		given:
			'a source and a timeout'
			def source = Broadcaster.<Integer> create()
			def reduced = source.timeout(1500, TimeUnit.MILLISECONDS, Stream.just(10))
			def error = null
			def value = reduced.doOnError(TimeoutException) {
				error = it
			}.tap()
			value.subscribe()
			println value.debug()

		when:
			'the first values are accepted on the source, paused just enough to refresh globalTimer until 6'
			source.onNext(1)
			sleep(500)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			source.onNext(5)
			sleep(2000)
			source.onNext(6)
			println value.debug()

		then:
			'last value known is 10 as the stream has used its fallback'
			!error
			value.get() == 10
	}

	def 'Errors can have a fallback'() {
		when:
			'A source stream emits next signals followed by an error'
			def res = []
			def myStream = Stream.yield { aSubscriber ->
				aSubscriber.emit('Three')
				aSubscriber.emit('Two')
				aSubscriber.emit('One')
				aSubscriber.failWith(new Exception())
				aSubscriber.emit('Zero')
			}

		and:
			'A fallback stream will emit values and complete'
			def myFallback = Stream.yield { aSubscriber ->
				aSubscriber.emit('0')
				aSubscriber.emit('1')
				aSubscriber.emit('2')
				aSubscriber.finish()
				aSubscriber.emit('3')
			}

		and:
			'fallback stream is assigned to source stream on any error'
			myStream.switchOnError(myFallback).consume(
					{ println(it); res << it },                          // onNext
					{ println("Error: " + it.message) }, // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == ['Three', 'Two', 'One', '0', '1', '2', 'complete']
	}


	def 'Errors can have a fallback return value'() {
		when:
			'A source stream emits next signals followed by an error'
			def res = []
			def myStream = Stream.yield { aSubscriber ->
				aSubscriber.onNext('Three')
				aSubscriber.onNext('Two')
				aSubscriber.onNext('One')
				aSubscriber.onError(new Exception())
			}

		and:
			'A fallback value will emit values and complete'
			myStream.onErrorReturn('Zero').consume(
					{ println(it); res << it },                          // onNext
					{ println("Error: " + it.message) }, // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == ['Three', 'Two', 'One', 'Zero', 'complete']
	}

	def 'Streams can be materialized'() {
		when:
			'A source stream emits next signals followed by complete'
			List res = []
			def myStream = Stream.yield { aSubscriber ->
				aSubscriber.emit('Three')
				aSubscriber.emit('Two')
				aSubscriber.emit('One')
				aSubscriber.finish()
			}

		and:
			'A materialized stream is consumed'
			myStream.materialize().consume(
					{ println(it); res << it.type.toString() },                          // onNext
					{ it.printStackTrace() },                          // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == ['NEXT', 'NEXT', 'NEXT', 'COMPLETE', 'complete']

		when:
			'A source stream emits next signals followed by complete'
			res = []
			myStream = Stream.yield { aSubscriber ->
				aSubscriber.emit('Three')
				aSubscriber.failWith(new Exception())
			}

		and:
			'A materialized stream is consumed'
			myStream.materialize().consume(
					{ println(it); res << it.type.toString() },                          // onNext
					{ it.printStackTrace() },                          // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == ['NEXT', 'ERROR', 'complete']
	}

	def 'Streams can be dematerialized'() {
		when:
			'A source stream emits next signals followed by complete'
			def res = []
			def myStream = Stream.yield { aSubscriber ->
				aSubscriber.onNext(Signal.next(1))
				aSubscriber.onNext(Signal.next(2))
				aSubscriber.onNext(Signal.next(3))
				aSubscriber.onNext(Signal.complete())
			}

		and:
			'A dematerialized stream is consumed'
			myStream.dematerialize().consume(
					{ println(it); res << it },                          // onNext
					{ it.printStackTrace() },                          // onError
					{ println("Sequence complete"); res << 'complete' }          // onComplete
			)

		then:
			res == [1, 2, 3, 'complete']
	}


	def 'Streams can be switched'() {
		when:
			'A source stream emits next signals followed by an error'
			def res = []
			def myStream = Stream.yield { aSubscriber ->
				aSubscriber.onNext('Three')
				aSubscriber.onNext('Two')
				aSubscriber.onNext('One')
			}

		and:
			'Another stream will emit values and complete'
			def myFallback = Stream.yield { aSubscriber ->
				aSubscriber.onNext('0')
				aSubscriber.onNext('1')
				aSubscriber.onNext('2')
				aSubscriber.onComplete()
			}

		and:
			'The streams are switched'
			def switched = Stream.switchOnNext(Stream.just(myStream, myFallback))
			switched.consume(
					{ println(Thread.currentThread().name + ' ' + it); res << it },                          // onNext
					{ println("Error: " + it.message) }, // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == ['Three', 'Two', 'One', '0', '1', '2', 'complete']
	}

	def 'Streams can be switched dynamically'() {
		when:
			'A source stream emits next signals followed by an error'
			def res = []
			def myStream = Stream.<Integer> yield { aSubscriber ->
				aSubscriber.onNext(1)
				aSubscriber.onNext(2)
				aSubscriber.onNext(3)
				aSubscriber.onComplete()
			}

		and:
			'The streams are switched'
			def switched = myStream.log('lol').switchMap { Stream.range(it, 3) }.log("after-lol")
			switched.consume(
					{ println(Thread.currentThread().name + ' ' + it); res << it },                          // onNext
					{ println("Error: " + it.message) }, // onError
					{ println("Sequence complete"); res << 'complete' }          // onCompleted
			)

		then:
			res == [1, 2, 3, 2, 3, 4, 3, 4, 5, 'complete']
	}


	def 'onOverflowDrop will miss events non requested'() {
		given:
			'a source and a timeout'
			def source = Broadcaster.<Integer> create()

			def value = null
			def tail = source.log("drop").onBackpressureDrop().doOnNext { value = it }.log('overflow-drop-test')
					.subscribeLater()

			tail.request(5)
			println tail.debug()

		when:
			'the first values are accepted on the source, but we only have 5 requested elements out of 6'
			source.onNext(1)
			source.onNext(2)
			source.onNext(3)
			source.onNext(4)
			source.onNext(5)

			println tail.debug()
			source.onNext(6)

		and:
			'we try to consume the tail to check if 6 has been buffered'
			tail.request(1)

		then:
			'last value known is 5'
			value == 5
	}

  def 'onBackpressureBlock will block events non requested'() {
	given:
	'a source and a timeout'
	def source = Broadcaster.<Integer> create()

	def value = null
	def tail = new TestSubscriber<>(5)

	source
			.log("block")
			.dispatchOn(asyncGroup)
			.onBackpressureBlock()
			.doOnNext { value = it }
			.log('overflow-block-test')
			.throttleRequest(100)
			.subscribe(tail)

	println tail.debug()

	when:
	'the first values are accepted on the source, but we only have 5 requested elements out of 6'
	source.onNext(1)
	source.onNext(2)
	source.onNext(3)
	source.onNext(4)
	source.onNext(5)

	println tail.debug()

	then:
	tail.awaitAndAssertValues(1,2,3,4,5)

	when:
	'we try to consume the tail to check if 6 has been buffered'
	source.onNext(6)
	tail.request(1)
	source.onComplete()

	then:
	'last value known is 5'
	tail.await()
		.assertValueCount(6)
		.assertComplete()
  }

	def 'A Stream can be throttled'() {
		given:
			'a source and a throttled stream'
			def source = Broadcaster.<Integer> create(true)
			long avgTime = 150l

			def reduced = source
					.throttleRequest(avgTime)
					.elapsed()
					.log('el')
					.take(10)
					.reduce(0l) { acc, next ->
				acc > 0l ? ((next.t1 + acc) / 2) : next.t1
			}

			def value = reduced.log().subscribeWith(Promise.prepare())
			println value.debug()

		when:
			'the first values are accepted on the source'
			for (int i = 0; i < 10000; i++) {
				source.onNext(1)
			}
			sleep(1500)
			println(((long) (value.await())) + " milliseconds on average")

		then:
			'the average elapsed time between 2 signals is greater than throttled time'
			value.peek() >= avgTime * 0.6

	}


	def 'A Stream can be throttled with a backoff policy as a stream'() {
		given:
			'a source and a throttled stream'
			def source = Broadcaster.<Integer> create(true)
			long avgTime = 150l

			def reduced = source
			.log('request')
					.requestWhen {
				it
						.flatMap { v -> Stream.interval(avgTime, TimeUnit.MILLISECONDS).map { 1l }
				}
			}
			.elapsed()
					.log('reduce')
					.take(10)

					.reduce(0l) { acc, next ->
				acc > 0l ? ((next.t1 + acc) / 2) : next.t1
			}

			def value = reduced.log('promise').subscribeWith(Promise.prepare())
			println value.debug()

		when:
			'the first values are accepted on the source'
			for (int i = 0; i < 10000; i++) {
				source.onNext(1)
			}
			println value.debug()
			println(((long) (value.await(10, TimeUnit.SECONDS))) + " milliseconds on average")

		then:
			'the average elapsed time between 2 signals is greater than throttled time'
			value.peek() >= avgTime * 0.6

	}

	def 'time-slices of average'() {
		given:
			'a source and a throttled stream'
			def source = Broadcaster.<Integer> create(true)
			def latch = new CountDownLatch(1)
			long avgTime = 150l

			def reduced = source
					.buffer()
					.throttleRequest(avgTime)
					.map { timeWindow -> timeWindow.size() }
					.doAfterTerminate { latch.countDown() }

			def value = reduced.tap()
			value.subscribe()
			println source.debug()

		when:
			'the first values are accepted on the source'
			for (int i = 0; i < 10000; i++) {
				source.onNext(1)
			}
			source.onComplete()
			latch.await(10, TimeUnit.SECONDS)
			println value.get()
			println source.debug()

		then:
			'the average elapsed time between 2 signals is greater than throttled time'
			value.get() > 1

	}


	def 'Collect will accumulate values from multiple threads'() {
		given:
			'a source and a collected stream'
			def sum = new AtomicInteger()
			int length = 1000
			int batchSize = 333
			int latchCount = length / batchSize
			def latch = new CountDownLatch(latchCount)
			def head = Broadcaster.<Integer> create()
			head.dispatchOn(asyncGroup).partition(3).consume {
				s ->
					s
							.dispatchOn(asyncGroup)
							.map { it }
							.buffer(batchSize)
							.consume { List<Integer> ints ->
						println ints.size()
						sum.addAndGet(ints.size())
						latch.countDown()
					}
			}
		when:
			'values are accepted into the head'
			(1..length).each { head.onNext(it) }
			latch.await(4, TimeUnit.SECONDS)

		then:
			'results contains the expected values'
			println head.debug()
			sum.get() == length - 1
	}


	def 'A Stream can be materialized for recursive signal or introspection'() {
		given:
			'a composable with an initial value'
			def stream = Stream.just('test')

		when:
			'the stream is retrieved and a consumer is dynamically added'
			def value = null

			stream.nest().consume { theStream ->
				theStream.consume {
					value = it
				}
			}

		then:
			'it is available'
			value == 'test'
	}


	def 'A Stream can be transformed into consumers for reuse'() {
		given:
			'a composable and its signal comsumers'
			def stream = Broadcaster.<String> create()
			def completeConsumer = stream.toCompleteConsumer()
			def nextConsumer = stream.toNextConsumer()

		when:
			'the stream is retrieved and consumer are triggered'
			def promise = stream.buffer().promise()
			nextConsumer.accept('test1')
			nextConsumer.accept('test2')
			nextConsumer.accept('test3')
			completeConsumer.accept(null)

		then:
			'it is available'
			promise.peek() == ['test1', 'test2', 'test3']
	}

	def 'A Stream can be transformed into error consumer for reuse'() {
		given:
			'a composable and its signal comsumers'
			def stream = Broadcaster.<String> create()
			def errorConsumer = stream.toErrorConsumer()
			def nextConsumer = stream.toNextConsumer()

		when:
			'the stream is retrieved and consumer are triggered'
			def promise = stream.buffer().promise()
			nextConsumer.accept('test1')
			nextConsumer.accept('test3')
			errorConsumer.accept(new Exception())
			promise.peek()

		then:
			'toList promise is under error'
			thrown(Exception)
	}

	def 'A Stream can re-subscribe its oldest parent on error signals'() {
		given:
			'a composable with an initial value'
			def stream = Stream.fromIterable(['test', 'test2', 'test3'])

		when:
			'the stream triggers an error for the 2 first elements and is using retry(2) to ignore them'
			def i = 0
			stream = stream
					.log('beforeObserve')
					.doOnNext {
				if (i++ < 2) {
					throw new RuntimeException()
				}
			}.retry(2)
					.log('afterRetry')
					.count()

			def value = stream.subscribeWith(Promise.prepare())

			println stream.debug()

		then:
			'3 values are passed since it is a cold stream resubscribed 2 times and finally managed to get the 3 values'
			value.peek() == 3

		when:
			'the stream triggers an error for the 2 first elements and is using retry() to ignore them'
			i = 0
			stream = Broadcaster.create()
			def tap = stream.doOnNext {
				if (i++ < 2) {
					throw new RuntimeException()
				}
			}.retry().count().subscribeWith(Promise.prepare())

			println tap.debug()
			println tap.debug()
			stream.onNext('test')
			stream.onNext('test2')
			stream.onNext('test3')
			stream.onComplete()
			println tap.debug()
			def res = tap.peek()

		then:
			'it is a hot stream and only 1 value (the most recent) is available'
			res == 1

		when:
			'the stream triggers an error for the 2 first elements and is using retry(matcher) to ignore them'
			i = 0
			stream = Stream.fromIterable(['test', 'test2', 'test3'])
			value = stream.doOnNext {
				if (i++ < 2) {
					throw new RuntimeException()
				}
			}.retry {
				i <= 2
			}.log().count().get()

		then:
			'3 values are passed since it is a cold stream resubscribed 1 time'
			value == 3

		when:
			'the stream triggers an error for the 2 first elements and is using retry(matcher) to ignore them'
			i = 0
			stream = Broadcaster.create()
			tap = stream.doOnNext {
				if (i++ < 2) {
					throw new RuntimeException()
				}
			}.retry {
				i <= 2
			}.count().subscribeWith(Promise.prepare())
			stream.onNext('test')
			stream.onNext('test2')
			stream.onNext('test3')
			stream.onComplete()
			println tap.debug()

		then:
			'it is a hot stream and only 1 value (the most recent) is available'
			tap.peek() == 1
	}

	def 'A Stream can re-subscribe its oldest parent on error signals after backoff stream'() {
		when:
			'when composable with an initial value'
			def counter = 0
			def value = Stream.yield {
				counter++
				it.onError(new RuntimeException("always fails $counter"))
			}.retryWhen { attempts ->
			  attempts.log('zipWith').zipWith(Stream.range(1, 3), { t1, t2 -> t2 }).flatMap { i ->
					println "delay retry by " + i + " second(s)"
				  println attempts.debug()
				Mono.delay(i)
				}
			}.subscribeWith(new TestSubscriber<>())
			println value.debug()

		then:
			'Promise completed after 3 tries'
			value.await().assertComplete()
			counter == 4

	}

	def 'A Stream can re-subscribe its oldest parent on complete signals'() {
		given:
			'a composable with an initial value'
			def stream = Stream.fromIterable(['test', 'test2', 'test3'])

		when:
			'using repeat(2) to ignore complete twice and resubscribe'
			def i = 0
			stream = stream.repeat(2).doOnNext { i++ }.count()

			def value = stream.get()

			println stream.debug()

		then:
			'9 values are passed since it is a cold stream resubscribed 2 times and finally managed to get the 9 values'
			value == 6L


		when:
			'using repeat() to ignore complete and resubscribe'
			stream = Broadcaster.create()
			i = 0
			stream.take(3).log().repeat().doOnNext { i++ }.subscribe()

			stream.onNext('test')
			stream.onNext('test2')
			stream.onNext('test3')

			stream.onNext('test')
			stream.onNext('test2')
			stream.onNext('test3')

			stream.onNext('test')
			stream.onNext('test2')
			stream.onNext('test3')

		then:
			'it is a hot stream and only 9 value (the most recent) is available'
			i == 9
	}

	def 'A Stream can re-subscribe its oldest parent on complete signals after backoff stream'() {
		when:
			'when composable with an initial value'
			def counter = 0
		Stream.yield {
				counter++
				it.onComplete()
			}.log('repeat').repeatWhen { attempts ->
				attempts.zipWith(Stream.range(1, 3)) { t1, t2 -> t2 }.flatMap { i ->
					println "delay repeat by " + i + " second(s)"
				  Mono.delay(i)
				}
			}.log('test').subscribe()
	  sleep 10000

		then:
			'Promise completed after 3 tries'
			counter == 4
	}

	def 'A Stream can re-subscribe its oldest parent on complete signals after backoff stream and volume'() {
		when:
			'when composable with an initial value'
			def counter = 0
		Stream.yield {
				counter++
				if(counter == 4){
				  it.onNext('hey')
				}
				it.onComplete()
			}.repeatWhen { attempts ->
				attempts.log('repeat').takeWhile{ println it; it == 0L }.flatMap { i ->
					println "delay repeat by 1 second"
				  Mono.delay(1)
				}
			}.log('test').subscribe()
	  sleep 10000

		then:
			'Promise completed after 3 tries'
			counter == 4
	}

	def 'A Stream can be timestamped'() {
		given:
			'a composable with an initial value and a relative time'
			def stream = Stream.just('test')
			def timestamp = System.currentTimeMillis()

		when:
			'timestamp operation is added and the stream is retrieved'
			def value = stream.timestamp().next().get()

		then:
			'it is available'
			value.t1 >= timestamp
			value.t2 == 'test'
	}

	def 'A Stream can be benchmarked'() {
		given:
			'a composable with an initial value and a relative time'
			def stream = Stream.just('test')
			long timestamp = System.currentTimeMillis()

		when:
			'elapsed operation is added and the stream is retrieved'
			def value = stream.doOnNext {
				sleep(1000)
			}.elapsed().next().get()

			long totalElapsed = System.currentTimeMillis() - timestamp

		then:
			'it is available'
			value.t1 <= totalElapsed
			value.t2 == 'test'
	}

	def 'A Stream can be sorted'() {
		given:
			'a composable with an initial value and a relative time'
			def stream = Stream.fromIterable([43, 32122, 422, 321, 43, 443311])

		when:
			'sorted operation is added and the stream is retrieved'
			def value = stream.bufferSort().buffer().tap()
			value.subscribe()

		then:
			'it is available'
			value.get() == [43, 43, 321, 422, 32122, 443311]

		when:
			'a composable with an initial value and a relative time'
			stream = Stream.fromIterable([43, 32122, 422, 321, 43, 443311])

		and:
			'sorted operation is added for up to 3 elements ordered at once and the stream is retrieved'
			value = stream.window(3).flatMap{ it.bufferSort() }.buffer(6).tap()
			value.subscribe()
			println value.debug()

		then:
			'it is available'
			value.get() == [43, 422, 32122, 43, 321, 443311]

		when:
			'a composable with an initial value and a relative time'
			stream = Stream.fromIterable([1, 2, 3, 4])

		and:
			'revese sorted operation is added and the stream is retrieved'
			value = stream
					.bufferSort({ a, b -> b <=> a } as Comparator<Integer>)
					.buffer()
					.tap()
			value.subscribe()

		then:
			'it is available'
			value.get() == [4, 3, 2, 1]
	}

	def 'A Stream can be limited'() {
		given:
			'a composable with an initial values'
			def stream = Stream.fromIterable(['test', 'test2', 'test3'])

		when:
			'take to the first 2 elements'
			def value = stream.take(2).tap()
	  		value.subscribe()

		then:
			'the second is the last available'
			value.get() == 'test2'

		when:
			'take until test2 is seen'
			def stream2 = Broadcaster.create()
			def value2 = stream2.log().takeWhile {
				'test2' != it
			}.tap()
			value2.subscribe()


		stream2.onNext('test1')
		stream2.onNext('test2')
		stream2.onNext('test3')

		then:
			'the second is the last available'
			value2.get() == 'test1'
	}


	def 'A Stream can be limited in time'() {
		given:
			'a composable with an initial values'
			def stream = Stream.range(0, Integer.MAX_VALUE)
					.dispatchOn(asyncGroup)

		when:
			'take to the first 2 elements'
		stream.take(2, TimeUnit.SECONDS).after().get()

		then:
			'the second is the last available'
			notThrown(Exception)
	}


	def 'A Stream can be skipped'() {
		given:
			'a composable with an initial values'
			def stream = Stream.fromIterable(['test', 'test2', 'test3'])

		when:
			'skip to the second element'
			def value = stream.skip(1).toList().get()

		then:
			'the first has been skipped'
			value == ['test2', 'test3']

		when:
			'skip until test2 is seen'
			stream = Broadcaster.create()
			def value2 = stream.skipWhile {
				'test1' == it
			}.buffer().promise()

			stream.onNext('test1')
			stream.onNext('test2')
			stream.onNext('test3')
			stream.onComplete()

		then:
			'the second is the last available'
			value2.peek() == ['test2', 'test3']
	}


	def 'A Stream can be skipped in time'() {
		given:
			'a composable with an initial values'
			def stream = Stream.range(0, 1000)
					.dispatchOn(asyncGroup)

		when:
			def promise = stream.log("skipTime").skip(2, TimeUnit.SECONDS).buffer().promise()

		then:
			!promise.await()
	}/*

	def "A Codec output can be streamed"() {
		given: "A delimiter stripping decoder and a buffer of delimited data"
			def codec = new DelimitedCodec<String, String>(true, StandardCodecs.STRING_CODEC)
			def string = 'Hello World!\nHello World!\nHello World!\n'
			def data1 = Buffer.wrap(string)
			string = 'Test\nTest\n'
			def data2 = Buffer.wrap(string)
			string = 'Test\nEnd\n'
			def data3 = Buffer.wrap(string)

		when: "data stream is decoded"
			def res = Streams.just(data1, data2, data3)
					.decode(codec)
					.toList()
					.await(5, TimeUnit.SECONDS)

		then: "the buffers have been correctly decoded"
			res == ['Hello World!', 'Hello World!', 'Hello World!', 'Test', 'Test', 'Test', 'End']
	}
*/

  def "error publishers don't fast fail"(){
	when: 'preparing error publisher'
		Publisher<Object> publisher = error(new IllegalStateException("boo"));
	Stream.from(publisher).onErrorReturn{ ex -> "error"}
	    def a = 1

	then: 'no exceptions'
		a == 1
  }

	static class SimplePojo {
		int id
		String title

		int hashcode() { id }
	}

	static class Reduction implements BiFunction<Integer, Integer, Integer> {
		@Override
		public Integer apply(Integer left, Integer right) {
			def result = right == null ? 1 : left * right
			println "${right} ${left} reduced to ${result}"
			return result
		}
	}
}
