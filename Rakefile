# -*- mode: ruby; coding: utf-8 -*-

gems_a = %w[ iudex-filter iudex-http iudex-http-test iudex-barc
             iudex-core iudex-httpclient-3 iudex-jetty-httpclient
             iudex-async-httpclient
             iudex-char-detector
             iudex-html iudex-simhash iudex-rome ]

gems_b = %w[ iudex-da iudex-worker ]

gems_c = %w[ iudex-brutefuzzy-protobuf iudex-brutefuzzy-service
             iudex ]

gems = gems_a + gems_b + gems_c

subtasks = %w[ clean install_deps test gem docs tag install push ]

task :default => :test

# Common task idiom for the common distributive subtasks
sel_tasks = Rake.application.top_level_tasks
sel_tasks << 'test' if sel_tasks.delete( 'default' )

sel_subtasks = ( subtasks & sel_tasks )

task :distribute do
  Rake::Task[ :multi ].invoke( sel_subtasks.join(' ') )
end

subtasks.each do |sdt|
  desc ">> Run '#{sdt}' on all gem sub-directories"
  task sdt => :distribute
end

# Install parent pom first on install
if sel_subtasks.include?( 'install' )
  task :distribute => :install_parent_pom
end

desc "Install maven parent pom only"
task :install_parent_pom do
  sh( "mvn -N install" )
  #FIXME: Use rmvn or load gem for this
end

desc "Run multi['task1 tasks2'] tasks over all sub gems"
task( :multi, :subtasks ) do |t,args|
  stasks = args.subtasks.split
  gems.each do |dir|
    Dir.chdir( dir ) do
      puts ">> cd #{dir}"
      sh( $0, *stasks )
    end
  end
end

desc "Run multish['shell command'] over all sub gem dirs"
task( :multish, :subtasks ) do |t,args|
  gems.each do |dir|
    Dir.chdir( dir ) do
      puts ">> cd #{dir}"
      sh( args.subtasks )
    end
  end
end

desc "Aggregated javadocs via Maven"
task :javadoc do
  sh( "mvn javadoc:aggregate" )
end

require 'rubygems'
gem 'rdoc'
require 'rdoc/task'

RDoc::Task.new do |rd|
  rd.main = "README.rdoc"
  rd.rdoc_dir = "doc" # FIXME: www/_site/rdoc?
  rd.title = "Iūdex RDoc"
  rd.options << "--charset=UTF-8"
  rd.rdoc_files.include( "README.rdoc" )
  gems.each do |gem|
    rd.rdoc_files.include( "#{gem}/README.rdoc",
                           "#{gem}/History.rdoc",
                           "#{gem}/lib/**/*.rb" )
  end
end

desc "Generate per-gem Gemfiles and jbundle install each"
task :generate_gemfile_per_gem do
  gems.each do |gname|
    Dir.chdir( gname ) do
      puts "=== Gemfile: #{gname} ==="

      File.open( 'Gemfile', 'w' ) do |fout|
        fout.write <<RUBY
# -*- ruby -*-
source :rubygems
gemspec :path => '.', :name => '#{gname}'
RUBY
      end

      sh "jbundle install --path #{ENV['HOME']}/.gem --local"
    end
  end
end

namespace :travis do
  desc "Setup and run tests in Travis CI environment"
  task :run => [ :test_pre, :setup_db, :migrate, :test_post ]

  task :setup_db do
    sh <<-SH
      psql -U postgres -c "CREATE USER iudex;"
      psql -U postgres -c "CREATE DATABASE iudex_test OWNER iudex;"
    SH
  end

  task :migrate do
    sh "bundle exec iudex-da/bin/iudex-migrate -d"
  end

  task :test_pre do
    gems_a.each do |dir|
      Dir.chdir( dir ) do
        puts ">> cd #{dir}"
        sh "bundle exec rake test gem"
      end
    end
  end

  task :test_post do
    gems_b.each do |dir|
      Dir.chdir( dir ) do
        puts ">> cd #{dir}"
        sh "bundle exec rake test gem"
      end
    end
  end

end
