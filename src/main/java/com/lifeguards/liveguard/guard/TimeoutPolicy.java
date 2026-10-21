package com.lifeguards.liveguard.guard;
final class TimeoutPolicy {
 private TimeoutPolicy(){}
 static int seconds(String category,int previous){
  int[] values=switch(category){case "HARASSMENT"->new int[]{600,86400,86400};case "PHISHING"->new int[]{600,600,86400};default->new int[]{0,600,86400};};
  return values[Math.min(previous,2)];
 }
}
