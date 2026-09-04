int found = bpKeys.BinarySearch(key);
return (found >= 0) ? bpVals[found] : null;
