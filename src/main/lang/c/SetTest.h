#ifndef _SetTest_h
#define _SetTest_h

#include <stdint.h>
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/* Clause SETS */

/* Clause CONCRETE_CONSTANTS */
/* Basic constants */
#define SetTest__MM 10
/* Array and record constants */


/* Clause CONCRETE_VARIABLES */

extern void SetTest__INITIALISATION(void);

/* Clause OPERATIONS */

extern void SetTest__Add(int32_t xx);
extern void SetTest__count(int32_t *rr);

#ifdef __cplusplus
}
#endif /* __cplusplus */


#endif /* _SetTest_h */
