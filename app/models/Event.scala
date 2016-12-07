/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Association du Paris Java User Group.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package models

import play.api.libs.json.Json
import library.Redis
import org.joda.time.{DateTimeZone, Instant, DateTime}
import java.util.Date

/**
 * Entity to store all CFP events generated by speaker such as "I submitted a talk" or
 * "my talk was accepted".
 *
 * Author: @nmartignole
 * Created: 11/11/2013 09:39
 */

/**
 * Event.
 * @param objRef is the unique ID of the object that was modified
 * @param uuid is the author, the person who did this change
 * @param msg is a description of what one did
 * @param date is the event ate
 */
case class Event(objRef: String, uuid: String, msg: String, date: Option[DateTime] = None)

object Event {
  implicit val eventFormat = Json.format[Event]

  def storeEvent(event: Event) = Redis.pool.withClient {
    client =>
      val jsEvent = Json.stringify(Json.toJson(event.copy(date = Some(new DateTime().toDateTime(DateTimeZone.forID("America/Sao_Paulo"))))))
      val tx = client.multi()
      val now = new Instant().getMillis
      tx.zadd("Events:V2:", now, jsEvent)
      tx.sadd("Events:V2:" + event.objRef, jsEvent)
      tx.set("Events:LastUpdated:" + event.objRef, now.toString)
      tx.exec()
  }

  def loadEvents(items: Int, page: Int): List[Event] = Redis.pool.withClient {
    client =>
      client.zrevrangeWithScores("Events:V2:", page * items, (page + 1) * items).flatMap {
        case (json: String, date: Double) => {
          val maybeEvent = Json.parse(json).asOpt
          val dateVal = new Instant(date.toLong)
          maybeEvent.map(event => event.copy(date = Some(dateVal.toDateTime)))
        }
      }
  }

  // Very fast O(1) operation
  def totalEvents() = Redis.pool.withClient {
    client =>
      client.zcard("Events:V2:")
  }

  def resetEvents() = Redis.pool.withClient{
    client=>
      client.del("Events:V2:")
      val allEvents = client.keys("Events:*")
      val tx=client.multi()
      allEvents.foreach{k:String=>
        tx.del(k)
      }
      tx.exec()
  }

  implicit object mostRecent extends Ordering[DateTime] {
    def compare(o1: DateTime, o2: DateTime) = o1.compareTo(o2)
  }

  def loadEventsForObjRef(objRef: String): List[Event] = Redis.pool.withClient {
    client =>
      client.smembers(s"Events:V2:$objRef").flatMap {
        json: String =>
          Json.parse(json).asOpt[Event]
      }.toList
  }

  def getMostRecentDateFor(objRef: String): Option[DateTime] = Redis.pool.withClient {
    client =>
      client.get("Events:LastUpdated:" + objRef).map {
        s =>
          new Instant().withMillis(s.toLong).toDateTime
      }
  }

  def speakerNotified(speaker: Speaker, allApproved: Set[Proposal], allRejected: Set[Proposal], allBackups: Set[Proposal]) = Redis.pool.withClient {
    client =>
      client.sadd("NotifiedSpeakers", speaker.uuid)
      // Pas de backup et rien d'approuvé
      if (allApproved.isEmpty && allBackups.isEmpty && allRejected.nonEmpty) {
        client.sadd("Notified:RefusedSpeakers", speaker.uuid)
      }
      if (allApproved.nonEmpty) {
        client.sadd("Notified:ApprovedSpeakers", speaker.uuid)
      }
      if (allApproved.isEmpty && allBackups.nonEmpty) {
        client.sadd("Notified:BackupSpeakers", speaker.uuid)
      }
  }

  def resetSpeakersNotified() = Redis.pool.withClient{
    client=>
      client.del("NotifiedSpeakers")
      client.del("Notified:RefusedSpeakers")
      client.del("Notified:ApprovedSpeakers")
      client.del("Notified:BackupSpeakers")
  }


}
