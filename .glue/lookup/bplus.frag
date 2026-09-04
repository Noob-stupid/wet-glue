int i = Collections.binarySearch(bpKeys, key);
return (i < 0) ? null : bpVals.get(i);
