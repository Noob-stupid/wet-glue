int found = Collections.binarySearch(bpKeys, key);
return (found < 0) ? null : bpVals.get(found);
