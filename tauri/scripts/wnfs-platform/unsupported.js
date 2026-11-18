// The browser never calls WNFS's Node disk adapters. Refuse if a caller tries.
const unsupported = () => { throw new Error('node-filesystem-unavailable-in-drive'); };
export default new Proxy({}, { get: () => unsupported });
