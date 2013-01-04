#--
# Copyright (c) 2008-2013 David Kellum
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

#### General test setup, logging, console output ####

require 'rubygems'
require 'bundler/setup'

require 'minitest/unit'
require 'minitest/autorun'

require 'rjack-logback'

module TestSetup
  include RJack
  Logback.config_console( :stderr => true, :thread => true )

  VERBOSE = ! ( ARGV & %w[ -v --verbose ] ).empty?

  if VERBOSE
    class TestOut
      def print( *a ); $stdout.puts( *a ); end
      def puts( *a );  $stdout.puts( *a ); end
    end
    MiniTest::Unit.output = TestOut.new

    Logback.root.level = Logback::DEBUG
  else
    Logback[ 'iudex.da.sequel' ].level = :warn
    Logback[ 'iudex.da.PoolDataSourceFactory' ].level = :warn
  end

end
