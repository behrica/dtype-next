(ns tech.v3.parallel.for
  "Serial and parallel iteration strategies across iterators and index spaces."
  (:import [java.util.concurrent ForkJoinPool Callable Future ForkJoinTask]
           [java.util ArrayDeque PriorityQueue Comparator Spliterator Iterator]
           [java.util.stream Stream]
           [java.util.function Consumer IntConsumer LongConsumer DoubleConsumer]
           [clojure.lang IFn]))


(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)


(def ^{:tag 'long
       :doc "Default batch size to allow reasonable safe-point access"}
  default-max-batch-size 64000)


(defn common-pool-parallelism
  "Integer number of threads used in the ForkJoinPool's common pool"
  ^long []
  (ForkJoinPool/getCommonPoolParallelism))


(defn indexed-map-reduce
  "Given a function that takes exactly 2 arguments, a start-index and a length,
call this function exactly N times where N is ForkJoinPool/getCommonPoolParallelism.
  Indexes will be split as evenly as possible among the invocations.  Uses
  ForkJoinPool/commonPool for parallelism.  The entire list of results (outputs of
  indexed-map-fn) are passed to reduce-fn; reduce-fn is called once.
  When called with 2 arguments the reduction function is dorun"
  ([^long num-iters indexed-map-fn reduce-fn max-batch-size]
   (if (or (< num-iters (* 2 (ForkJoinPool/getCommonPoolParallelism)))
           (ForkJoinTask/inForkJoinPool))
     (reduce-fn [(indexed-map-fn 0 num-iters)])
     (let [num-iters (long num-iters)
           max-batch-size (long max-batch-size)
           parallelism (ForkJoinPool/getCommonPoolParallelism)
           group-size (quot num-iters parallelism)
           ;;max batch size is setup so that we can play nice with garbage collection
           ;;safepoint mechanisms
           group-size (min group-size max-batch-size)
           n-groups (quot (+ num-iters (dec group-size))
                          group-size)
           ;;Get pairs of (start-idx, len) to launch callables
           common-pool (ForkJoinPool/commonPool)]
       (->> (range n-groups)
            ;;Force start of execution with mapv
            (mapv (fn [^long callable-idx]
                    (let [group-start (* callable-idx group-size)
                          group-end (min (+ group-start group-size) num-iters)
                          group-len (- group-end group-start)
                          callable #(indexed-map-fn group-start group-len)]
                      (.submit common-pool ^Callable callable))))
            (map #(.get ^Future %))
            (reduce-fn)))))
  ([num-iters indexed-map-fn reduce-fn]
   (indexed-map-reduce num-iters indexed-map-fn reduce-fn default-max-batch-size))
  ([num-iters indexed-map-fn]
   (indexed-map-reduce num-iters indexed-map-fn dorun default-max-batch-size)))


(defn launch-parallel-for
  "Legacy name.  See indexed-map-reduce"
  ([^long num-iters indexed-map-fn reduce-fn]
   (indexed-map-reduce num-iters indexed-map-fn reduce-fn))
  ([num-iters indexed-map-fn]
   (indexed-map-reduce num-iters indexed-map-fn)))


(defmacro parallel-for
  "Like clojure.core.matrix.macros c-for except this expects index that run from 0 ->
  num-iters.  Idx is named idx-var and body will be called for each idx in parallel.
  Uses forkjoinpool's common pool for parallelism.  Assumed to be side effecting;
  returns nil."
  [idx-var num-iters & body]
  `(let [num-iters# (long ~num-iters)]
     (if (< num-iters# (* 2 (ForkJoinPool/getCommonPoolParallelism)))
       (dotimes [~idx-var num-iters#]
         ~@body)
       (indexed-map-reduce
        num-iters#
        (fn [^long group-start# ^long group-len#]
          (dotimes [idx# group-len#]
            (let [~idx-var (+ idx# group-start#)]
              ~@body)))))))


(defn as-spliterator
  "Convert a stream or a spliterator into a spliterator."
  ^Spliterator [item]
  (cond
    (instance? Spliterator item)
    item
    (instance? Stream item)
    (.spliterator ^Stream item)))

(defn convertible-to-iterator?
  "True when this is an iterable or a stream."
  [item]
  (or (instance? Iterable item)
      (instance? Stream item)))


(defn ->iterator
  "Convert a stream or an iterable into an iterator."
  ^Iterator [item]
  (cond
    (instance? Iterable item)
    (.iterator ^Iterable item)
    (instance? Stream item)
    (.iterator ^Stream item)
    :else
    (throw (Exception. (format "Item type %s has no iterator"
                               (type item))))))


(defmacro doiter
  "Execute body for every item in the iterable.  Expecting side effects, returns nil."
  [varname iterable & body]
  `(let [iter# (->iterator ~iterable)]
     (loop [continue?# (.hasNext iter#)]
       (when continue?#
         (let [~varname (.next iter#)]
           ~@body
           (recur (.hasNext iter#)))))))


(defn ->consumer
  "Convert a generic object into a consumer.  Works for any java.util.consumer
  and a Clojure IFn.  Returns an implementation of java.util.Consumer"
  ^Consumer [consumer]
  (cond
    (instance? Consumer consumer)
    consumer
    (instance? IntConsumer consumer)
    (let [^IntConsumer consumer consumer]
      (reify Consumer
        (accept [this value]
          (.accept consumer (int value)))))
    (instance? LongConsumer consumer)
    (let [^LongConsumer consumer consumer]
      (reify Consumer
        (accept [this value]
          (.accept consumer (long value)))))
    (instance? DoubleConsumer consumer)
    (let [^DoubleConsumer consumer consumer]
      (reify Consumer
        (accept [this value]
          (.accept consumer (double value)))))
    (instance? IFn consumer)
    (reify Consumer
      (accept [this value]
        (consumer value)))
    :else
    (throw (Exception.
            (format "Argument %s is not convertible to a Consumer"
                    consumer)))))


(defn consume!
  "Consume (terminate) a sequence or stream.  If the stream is parallel
  then the consumer had better be threadsafe.
  Returns nil."
  [consumer item]
  (let [consumer (->consumer consumer)]
    (if-let [spliterator (as-spliterator item)]
      (.forEachRemaining ^Spliterator spliterator consumer)
      (if (convertible-to-iterator? item)
        (doiter
         value item
         (.accept consumer value))))))


(defn spliterator-map-reduce
  "Given a spliterator, a function from spliterator to scalar and a
  reduction function do an efficient parallelized reduction."
  [^Spliterator data map-fn reduce-fn]
  (let [src-spliterator data
        n-cpus (common-pool-parallelism)
        n-splits (long (Math/ceil (Math/log n-cpus)))
        spliterators (loop [idx 0
                            spliterators [src-spliterator]]
                       (if (< idx n-splits)
                         (recur (unchecked-inc idx)
                                ;;Splitting a spliterator is a side-effecting operation
                                ;;so we can't allow this to be lazy
                                (->> spliterators
                                     (mapcat #(when % [(.trySplit ^Spliterator %) %]))
                                     (remove nil?)
                                     vec))
                         spliterators))
        common-pool (ForkJoinPool/commonPool)]
    (->> spliterators
         ;;Launch all the tasks
         (mapv #(.submit common-pool ^Callable (fn [] (map-fn %))))
         (map #(.get ^Future %))
         reduce-fn)))
