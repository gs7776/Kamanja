package com.ligadata.RDD

import scala.language.implicitConversions
import scala.reflect.{ classTag, ClassTag }
import org.apache.log4j.Logger
import com.ligadata.FatafatBase._
import scala.collection.mutable.ArrayBuffer

class RddImpl[T <: Any] extends RDD[T] {
  private val collections = ArrayBuffer[T]()

  override def iterator: Iterator[T] = collections.iterator

  override def map[U: ClassTag](f: T => U): RDD[U] = {
    val newrdd = new RddImpl[U]()
    newrdd.collections ++= collections.iterator.map(f)
    newrdd
  }

  override def map[U: ClassTag](tmRange: TimeRange, f: T => U): RDD[U] = {
    throw new Exception("Unhandled function map")
  }

  override def flatMap[U: ClassTag](f: T => TraversableOnce[U]): RDD[U] = {
    val newrdd = new RddImpl[U]()
    newrdd.collections ++= collections.iterator.flatMap(f)
    newrdd
  }

  override def filter(f: T => Boolean): RDD[T] = {
    val newrdd = new RddImpl[T]()
    newrdd.collections ++= collections.iterator.filter(f)
    newrdd
  }

  override def filter(tmRange: TimeRange, f: T => Boolean): RDD[T] = {
    val newrdd = new RddImpl[T]()
    newrdd.collections ++= collections.iterator.filter(f)
    newrdd
  }

  override def union(other: RDD[T]): RDD[T] = {
    throw new Exception("Unhandled function union")
  }

  override def intersection(other: RDD[T]): RDD[T] = {
    throw new Exception("Unhandled function intersection")
  }

  override def groupBy[K](f: T => K): RDD[(K, Iterable[T])] = null

  override def foreach(f: T => Unit): Unit = {
    collections.iterator.foreach(f)
  }

  override def toArray[T: ClassTag]: Array[T] = {
    throw new Exception("Unhandled function toArray")
    /* return collections.toArray */
  }

  override def subtract(other: RDD[T]): RDD[T] = null

  override def count(): Long = size()

  override def size(): Long = collections.size

  override def first(): Option[T] = {
    if (collections.size > 0)
      return Some(collections(0))
    None
  }

  // def last(index: Int): Option[T]
  override def last(): Option[T] = {
    if (collections.size > 0)
      return Some(collections(collections.size - 1))
    None
  }

  override def top(num: Int): Array[T] = null

  override def max[U: ClassTag](f: (Option[U], T) => U): Option[U] = {
    None
  }

  override def min[U: ClassTag](f: (Option[U], T) => U): Option[U] = None

  override def isEmpty(): Boolean = collections.isEmpty

  override def keyBy[K](f: T => K): RDD[(K, T)] = null
}
