#--
# Copyright (c) 2011-2015 David Kellum
#
# Licensed under the Apache License, Version 2.0 (the "License"); you
# may not use this file except in compliance with the License.  You
# may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.  See the License for the specific language governing
# permissions and limitations under the License.
#++

require 'iudex-http-test/base'

require 'thread'
require 'socket'

module Iudex::HTTP::Test

  class BrokenServer
    attr_accessor :port

    def initialize
      @port = 19293
      @server = nil
      @log = RJack::SLF4J[ self.class ]
    end

    def start
      @server = TCPServer.new( @port )
    end

    def accept_thread( &block )
      Thread.new do
        java.lang.Thread::currentThread.name = 'accept' # for logging
        accept( &block )
      end
    end

    def accept
      sock = @server.accept
      yield sock if block_given?
    rescue Errno::EPIPE => x
      @log.warn( "In accept:", x )
    end

    def stop
      if @server
        @server.close
        @server = nil
        true
      end
    end

  end

end
