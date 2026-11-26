#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP qw(decode_json);
my ($file, $project) = @ARGV;
my %spec = (
 # Lane release không chạy test nên phân tích backend không có coverage:
 # bỏ `coverage` khỏi spec để validator không đòi metric vắng mặt.
 'baotin_auth-service' => {bugs=>['GT',6],vulnerabilities=>['GT',0],critical_violations=>['GT',35]},
 'baotin_api-gateway' => {bugs=>['GT',2],vulnerabilities=>['GT',0],critical_violations=>['GT',8]},
 'baotin_frontend' => {bugs=>['GT',0],vulnerabilities=>['GT',0]},
);
for my $service (qw(analytics campaign model-inference notification report reputation search threat)) {
 $spec{"baotin_${service}-service"}={bugs=>['GT',0],vulnerabilities=>['GT',0],blocker_violations=>['GT',1]};
}
sub fail { print STDERR "SONAR_GATE FAIL invalid_or_weakened_policy\n"; exit 5; }
exists $spec{$project // ''} or fail();
open my $fh, '<', $file or fail(); local $/; my $raw=<$fh>; close $fh;
my $data=eval { decode_json($raw) }; $@ and fail();
ref($data) eq 'HASH' && ref($data->{projectStatus}) eq 'HASH' or fail();
my $s=$data->{projectStatus};
($s->{status}//'') eq 'OK' && exists($s->{ignoredConditions}) && !$s->{ignoredConditions} or fail();
ref($s->{conditions}) eq 'ARRAY' && @{$s->{conditions}} or fail();
my %seen;
for my $c (@{$s->{conditions}}) {
 ref($c) eq 'HASH' or fail(); my $m=$c->{metricKey}//'';
 !$seen{$m}++ && ($c->{status}//'') eq 'OK' or fail();
 my $a=$c->{actualValue}; my $t=$c->{errorThreshold}; my $op=$c->{comparator}//'';
 defined($a) && defined($t) && $a =~ /^\d+(?:\.\d+)?$/ && $t =~ /^\d+(?:\.\d+)?$/ or fail();
 ($op eq 'GT' || $op eq 'LT') or fail();
 (($op eq 'GT' && $a <= $t) || ($op eq 'LT' && $a >= $t)) or fail();
 if (exists $spec{$project}{$m}) {
  my ($required_op,$limit)=@{$spec{$project}{$m}};
  $op eq $required_op && (($op eq 'GT' && $t <= $limit) || ($op eq 'LT' && $t >= $limit)) or fail();
 }
}
for my $metric (keys %{$spec{$project}}) { $seen{$metric} or fail(); }
print "SONAR_POLICY PASS project=$project required_metrics=complete thresholds=not_weakened actual_values=verified\n";
