import java.io.IOException

import zio.nio._
import zio.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel}
import zio.clock.Clock
import zio.console._
import zio.{App, ZIO, _}


object Main extends App {
  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = {
    appLogic.foldM(
      err => putStrLn(s"Execution Failed with: $err") *> ZIO.succeed(1),
      _   => ZIO.succeed(0)
    )
  }

  val appLogic: ZIO[Console with Clock, Throwable, Unit] =
    for {
      address <- SocketAddress.inetSocketAddress("127.0.0.1", 1337)
      socket  <- AsynchronousServerSocketChannel()
      _       <- socket.bind(address)
      ref     <- Ref.make[Int](0)
      _       <- awaitConnection(ref, socket, socketChannelWorker)
    } yield ()


  /*
   * Accept a connection from the server, fork the worker on it, and
   * loop to wait for next connection
   */
  def awaitConnection(ref: Ref[Int], socket: AsynchronousServerSocketChannel, worker: Int => AsynchronousSocketChannel => ZIO[Console with Clock, Throwable, Unit]): ZIO[Console with Clock, Throwable, Nothing] = {
    for {
      i    <- ref.update(_+1)
      _    <- putStrLn("accept")
      _    <- socket.accept.flatMap(s => socketChannelWorker(i)(s).catchSome { case _: IOException => s.close}.fork)
      loop <- awaitConnection(ref, socket, worker)
    } yield loop
  }

  /*
   * From a connected AsynchronousSocketChannel, read forever, until connection termination
   */
  def socketChannelWorker(i:Int)(channel: AsynchronousSocketChannel): ZIO[Console with Clock, Throwable, Unit] = {
      for {
        chunk  <- channel.read(16)
        bytes  = chunk.toArray
        text   = bytes.map(_.toChar).map( c => if (c.isControl) s"${c.toHexString.toUpperCase}" else s"$c" ).mkString
        _      <- channel.write(chunk)
        _      <- putStrLn(s"content for con $i: " + text)
      } yield ()
  }.whenM(channel.isOpen)
    .forever
    .ensuring(putStrLn("closing channel") *> channel.close.orDie)
}
